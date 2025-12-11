#include "srt_streamer.h"
#include <android/log.h>
#include <chrono>
#include <cstdlib>   // setenv
#include <iomanip>
#include <mutex>
#include <sstream>
#include <thread>
#include <atomic>

#define LOG_TAG "SrtStreamer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

#if GSTREAMER_AVAILABLE
#include <gst/gst.h>
#include <gst/app/gstappsrc.h>
#endif

namespace orbistream {

class SrtStreamer::Impl {
public:
    Impl() = default;
    ~Impl() { cleanup(); }

    bool createPipeline(const StreamConfig& config);
    bool start();
    void stop();
    bool isStreaming() const { return streaming; }
    StreamStats getStats() const;
    
    void pushVideoFrame(const uint8_t* data, size_t size, 
                        int width, int height, int64_t timestampNs);
    void pushAudioSamples(const uint8_t* data, size_t size,
                          int sampleRate, int channels, int64_t timestampNs);

    StateCallback stateCallback;
    StatsCallback statsCallback;
    ErrorCallback errorCallback;

private:
    void cleanup();
    std::string buildPipelineString(const StreamConfig& config);
    
#if GSTREAMER_AVAILABLE
    GstElement* pipeline = nullptr;
    GstElement* videoAppSrc = nullptr;
    GstElement* audioAppSrc = nullptr;
    GMainLoop* mainLoop = nullptr;
    std::thread mainLoopThread;
    bool videoCapsSet = false;
    int lastVideoWidth = 0;
    int lastVideoHeight = 0;
#endif

    StreamConfig currentConfig;
    std::atomic<bool> streaming{false};
    mutable std::mutex statsMutex;
    StreamStats stats;
    std::chrono::steady_clock::time_point startTime;
};

// Static GStreamer initialization
bool SrtStreamer::initGStreamer() {
#if GSTREAMER_AVAILABLE
    static bool initialized = false;
    if (!initialized) {
        // Verbose debug for video path to inspect SPS/PPS/IDR behavior
        setenv("GST_DEBUG", "x264enc:5,h264parse:5,mpegtsmux:4,appsrc:4,queue:3,srtsink:4,udpsink:4", 1);
        setenv("GST_DEBUG_NO_COLOR", "1", 1);
        gst_init(nullptr, nullptr);
        initialized = true;
        LOGI("GStreamer initialized");
    }
    return true;
#else
    LOGI("GStreamer not available - using stub implementation");
    return true;
#endif
}

std::string SrtStreamer::Impl::buildPipelineString(const StreamConfig& config) {
    // Build the GStreamer pipeline string for streaming
    // 
    // The pipeline uses appsrc for both video and audio so we can push
    // frames from the Android camera and microphone.
    //
    // Video path: appsrc -> videoconvert -> x264enc -> h264parse
    // Audio path: appsrc -> audioconvert -> voaacenc -> aacparse
    // Both paths mux into mpegtsmux -> (srtsink or udpsink)

    const char* transportStr = (config.transport == TransportMode::UDP) ? "UDP" : "SRT";
    
    LOGI("=== STREAMING CONFIG ===");
    LOGI("Transport: %s", transportStr);
    LOGI("Target: %s:%d", config.srtHost.c_str(), config.srtPort);
    LOGI("Video: %dx%d @ %d fps, bitrate %d bps", 
         config.videoWidth, config.videoHeight, config.frameRate, config.videoBitrate);
    LOGI("Audio: %d Hz, bitrate %d bps", config.sampleRate, config.audioBitrate);
    if (config.useProxy && config.transport == TransportMode::UDP) {
        LOGI("Bondix: Enabled - reliability handled by tunnel");
    }
    LOGI("========================");

    std::stringstream ss;
    
    // Video source from app
    // - do-timestamp=true: GStreamer assigns timestamps from pipeline clock
    // - is-live=true: Source provides data in real-time
    // - format=time: Timestamps are in nanoseconds
    ss << "appsrc name=video_src format=time is-live=true do-timestamp=true "
       << "caps=\"video/x-raw,format=NV21,width=" << config.videoWidth 
       << ",height=" << config.videoHeight << ",framerate=" << config.frameRate << "/1\" ! ";
    
    // Video processing chain (matching MCRBox pattern):
    // - videorate: ensures consistent frame timing (critical for camera input!)
    // - videoconvert -> videoscale -> caps to target WxH  
    // - x264enc with zerolatency tune (MCRBox uses this successfully)
    // - Direct to mux (no h264parse - MCRBox doesn't use it)
    ss << "videorate drop-only=true skip-to-first=true ! "
       << "videoconvert ! "
       << "videoscale ! video/x-raw,width=" << config.videoWidth << ",height=" << config.videoHeight << " ! "
       << "x264enc name=video_enc tune=zerolatency speed-preset=ultrafast bitrate=" << (config.videoBitrate / 1000)
       << " key-int-max=" << (config.frameRate * 2)  // GOP = 2 seconds like MCRBox
       << " threads=2 ! "
       << "queue name=video_queue max-size-buffers=3 leaky=downstream ! mux. ";
    
    // Audio processing chain (matching video pattern with rate element):
    // - audiorate: ensures consistent audio timing (like videorate for video)
    // - audioconvert + audioresample: format conversion
    // - voaacenc: AAC encoding
    // - leaky queue: drops old samples if backed up
    ss << "appsrc name=audio_src format=time is-live=true do-timestamp=true "
       << "caps=\"audio/x-raw,format=S16LE,layout=interleaved,rate=" << config.sampleRate 
       << ",channels=" << config.audioChannels << "\" ! "
       << "audiorate skip-to-first=true ! "
       << "audioconvert ! "
       << "audioresample ! "
       << "voaacenc bitrate=" << config.audioBitrate << " ! "
       << "aacparse ! "
       << "queue name=audio_queue max-size-buffers=3 leaky=downstream ! mux. ";
    
    // Muxer - alignment=7 aligns to MPEG-TS packet boundaries (like MCRBox)
    ss << "mpegtsmux name=mux alignment=7 ! ";
    
    // Output sink based on transport mode
    if (config.transport == TransportMode::UDP) {
        // UDP output - relies on Bondix for reliability
        // When used with SOCKS5 UDP relay, this goes through the bonded tunnel
        ss << "udpsink name=udp_sink host=" << config.srtHost 
           << " port=" << config.srtPort
           << " sync=false async=false";
        LOGI("UDP sink: host=%s port=%d", config.srtHost.c_str(), config.srtPort);
    } else {
        // SRT output - has its own reliability (use when not using Bondix)
        std::string srtUri = "srt://" + config.srtHost + ":" + std::to_string(config.srtPort);
        if (!config.streamId.empty()) {
            srtUri += "?streamid=" + config.streamId;
        }
        
        std::string srtSinkProps = "uri=\"" + srtUri + "\" mode=caller latency=500 wait-for-connection=false";
        
        if (!config.streamId.empty()) {
            srtSinkProps += " streamid=\"" + config.streamId + "\"";
        }
        if (!config.passphrase.empty()) {
            srtSinkProps += " passphrase=\"" + config.passphrase + "\"";
        }
        
        ss << "srtsink name=srt_sink " << srtSinkProps;
        LOGI("SRT sink: %s", srtSinkProps.c_str());
    }
    
    return ss.str();
}

bool SrtStreamer::Impl::createPipeline(const StreamConfig& config) {
#if GSTREAMER_AVAILABLE
    cleanup();
    
    currentConfig = config;
    std::string pipelineStr = buildPipelineString(config);
    
    LOGI("=== CREATING GSTREAMER PIPELINE ===");
    LOGI("Pipeline string length: %zu chars", pipelineStr.length());
    LOGD("Full pipeline: %s", pipelineStr.c_str());
    
    GError* error = nullptr;
    pipeline = gst_parse_launch(pipelineStr.c_str(), &error);
    
    if (error) {
        LOGE("!!! PIPELINE CREATION FAILED !!!");
        LOGE("Error code: %d", error->code);
        LOGE("Error message: %s", error->message);
        if (errorCallback) {
            errorCallback(error->message);
        }
        g_error_free(error);
        return false;
    }
    
    if (!pipeline) {
        LOGE("!!! Pipeline is null after creation !!!");
        return false;
    }
    
    LOGI("Pipeline created successfully");
    
    // Get appsrc elements for pushing data
    videoAppSrc = gst_bin_get_by_name(GST_BIN(pipeline), "video_src");
    audioAppSrc = gst_bin_get_by_name(GST_BIN(pipeline), "audio_src");
    GstElement* videoEnc = gst_bin_get_by_name(GST_BIN(pipeline), "video_enc");
    
    if (!videoAppSrc || !audioAppSrc) {
        LOGE("Failed to get appsrc elements (video=%p, audio=%p)", videoAppSrc, audioAppSrc);
        cleanup();
        return false;
    }
    
    // Configure video appsrc for streaming
    g_object_set(videoAppSrc,
        "stream-type", 0,  // GST_APP_STREAM_TYPE_STREAM
        "format", GST_FORMAT_TIME,
        nullptr);
    
    // Configure audio appsrc for streaming
    g_object_set(audioAppSrc,
        "stream-type", 0,
        "format", GST_FORMAT_TIME,
        nullptr);

    // Pad probe on x264enc src to inspect first few buffers (NAL headers, SPS/PPS/IDR presence)
    if (videoEnc) {
        GstPad* encSrc = gst_element_get_static_pad(videoEnc, "src");
        if (encSrc) {
            gst_pad_add_probe(encSrc, GST_PAD_PROBE_TYPE_BUFFER,
                [](GstPad*, GstPadProbeInfo* info, gpointer) -> GstPadProbeReturn {
                    static int logged = 0;
                    if (logged >= 10) return GST_PAD_PROBE_OK; // keep log noise low
                    GstBuffer* buf = GST_PAD_PROBE_INFO_BUFFER(info);
                    if (!buf) return GST_PAD_PROBE_OK;
                    GstMapInfo map;
                    if (!gst_buffer_map(buf, &map, GST_MAP_READ))
                        return GST_PAD_PROBE_OK;
                    
                    // Scan ENTIRE buffer for NAL types (not just first 64 bytes)
                    bool hasIDR = false, hasSPS = false, hasPPS = false;
                    std::vector<int> nalTypes;
                    for (size_t i = 0; i + 4 < map.size; ++i) {
                        // Look for start codes
                        if (map.data[i] == 0x00 && map.data[i+1] == 0x00) {
                            size_t hdr = 0;
                            if (map.data[i+2] == 0x01) {
                                hdr = i + 3;
                            } else if (map.data[i+2] == 0x00 && i + 4 < map.size && map.data[i+3] == 0x01) {
                                hdr = i + 4;
                            }
                            if (hdr > 0 && hdr < map.size) {
                                int nt = map.data[hdr] & 0x1F;
                                // Only add unique types
                                bool found = false;
                                for (int t : nalTypes) if (t == nt) { found = true; break; }
                                if (!found) nalTypes.push_back(nt);
                                if (nt == 5) hasIDR = true;
                                if (nt == 7) hasSPS = true;
                                if (nt == 8) hasPPS = true;
                            }
                        }
                    }
                    
                    std::ostringstream nalList;
                    for (size_t i = 0; i < nalTypes.size(); ++i) {
                        nalList << nalTypes[i] << (i + 1 == nalTypes.size() ? "" : ",");
                    }
                    
                    LOGI("h264probe buf=%zu nal_types=[%s] IDR=%d SPS=%d PPS=%d", 
                         map.size, nalList.str().c_str(), hasIDR, hasSPS, hasPPS);
                    
                    gst_buffer_unmap(buf, &map);
                    logged++;
                    return GST_PAD_PROBE_OK;
                },
                nullptr, nullptr);
            gst_object_unref(encSrc);
        }
        gst_object_unref(videoEnc);
    }

    // Pad probe on video appsrc sink to confirm camera frames enter pipeline
    if (videoAppSrc) {
        GstPad* vsrc_sink = gst_element_get_static_pad(videoAppSrc, "src");
        if (vsrc_sink) {
            gst_pad_add_probe(vsrc_sink, GST_PAD_PROBE_TYPE_BUFFER,
                [](GstPad*, GstPadProbeInfo* info, gpointer) -> GstPadProbeReturn {
                    static int vlogged = 0;
                    if (vlogged >= 5) return GST_PAD_PROBE_OK;
                    GstBuffer* buf = GST_PAD_PROBE_INFO_BUFFER(info);
                    if (buf) {
                        LOGI("video_src incoming buffer size=%zu", (size_t)gst_buffer_get_size(buf));
                        vlogged++;
                    }
                    return GST_PAD_PROBE_OK;
                },
                nullptr, nullptr);
            gst_object_unref(vsrc_sink);
        }
    }
    
    LOGI("Pipeline created successfully");
    return true;
#else
    currentConfig = config;
    LOGI("Stub pipeline created (GStreamer not available)");
    return true;
#endif
}

bool SrtStreamer::Impl::start() {
#if GSTREAMER_AVAILABLE
    if (!pipeline) {
        LOGE("!!! No pipeline to start !!!");
        return false;
    }
    
    LOGI("=== STARTING SRT STREAM ===");
    LOGI("Setting pipeline to PLAYING state...");
    
    // Reset video caps tracking
    videoCapsSet = false;
    lastVideoWidth = 0;
    lastVideoHeight = 0;
    
    GstStateChangeReturn ret = gst_element_set_state(pipeline, GST_STATE_PLAYING);
    
    const char* stateChangeStr;
    switch (ret) {
        case GST_STATE_CHANGE_SUCCESS: stateChangeStr = "SUCCESS"; break;
        case GST_STATE_CHANGE_ASYNC: stateChangeStr = "ASYNC (connecting...)"; break;
        case GST_STATE_CHANGE_NO_PREROLL: stateChangeStr = "NO_PREROLL"; break;
        case GST_STATE_CHANGE_FAILURE: stateChangeStr = "FAILURE"; break;
        default: stateChangeStr = "UNKNOWN"; break;
    }
    LOGI("State change result: %s", stateChangeStr);
    
    if (ret == GST_STATE_CHANGE_FAILURE) {
        LOGE("!!! FAILED TO START PIPELINE !!!");
        LOGE("SRT connection may have failed - check host/port");
        if (errorCallback) {
            errorCallback("Failed to start streaming pipeline - SRT connection failed?");
        }
        return false;
    }
    
    streaming = true;
    startTime = std::chrono::steady_clock::now();
    
    // Start main loop in separate thread for bus messages
    mainLoop = g_main_loop_new(nullptr, FALSE);
    mainLoopThread = std::thread([this]() {
        LOGI("GStreamer main loop started");
        g_main_loop_run(mainLoop);
        LOGI("GStreamer main loop ended");
    });
    
    LOGI("=== SRT STREAM STARTED ===");
    LOGI("Streaming to: %s:%d", currentConfig.srtHost.c_str(), currentConfig.srtPort);
    if (stateCallback) {
        stateCallback(true, "Streaming started");
    }
    
    return true;
#else
    streaming = true;
    startTime = std::chrono::steady_clock::now();
    LOGI("Stub streaming started");
    if (stateCallback) {
        stateCallback(true, "Streaming started (stub mode)");
    }
    return true;
#endif
}

void SrtStreamer::Impl::stop() {
#if GSTREAMER_AVAILABLE
    if (!streaming) {
        LOGD("Stop called but not streaming");
        return;
    }
    
    LOGI("=== STOPPING SRT STREAM ===");
    streaming = false;
    
    if (pipeline) {
        LOGI("Setting pipeline to NULL state...");
        gst_element_set_state(pipeline, GST_STATE_NULL);
    }
    
    if (mainLoop) {
        LOGI("Stopping GStreamer main loop...");
        g_main_loop_quit(mainLoop);
        if (mainLoopThread.joinable()) {
            mainLoopThread.join();
        }
        g_main_loop_unref(mainLoop);
        mainLoop = nullptr;
    }
    
    // Log final stats
    LOGI("=== STREAM ENDED ===");
    LOGI("Total bytes sent: %llu", (unsigned long long)stats.bytesSent);
    LOGI("Stream duration: %llu ms", (unsigned long long)stats.streamTimeMs);
    
    if (stateCallback) {
        stateCallback(false, "Streaming stopped");
    }
#else
    streaming = false;
    LOGI("Stub streaming stopped");
    if (stateCallback) {
        stateCallback(false, "Streaming stopped");
    }
#endif
}

void SrtStreamer::Impl::cleanup() {
#if GSTREAMER_AVAILABLE
    stop();
    
    if (videoAppSrc) {
        gst_object_unref(videoAppSrc);
        videoAppSrc = nullptr;
    }
    if (audioAppSrc) {
        gst_object_unref(audioAppSrc);
        audioAppSrc = nullptr;
    }
    if (pipeline) {
        gst_object_unref(pipeline);
        pipeline = nullptr;
    }
    
    // Reset dynamic caps state
    videoCapsSet = false;
    lastVideoWidth = 0;
    lastVideoHeight = 0;
#endif
}

StreamStats SrtStreamer::Impl::getStats() const {
    std::lock_guard<std::mutex> lock(statsMutex);
    StreamStats currentStats = stats;
    
    if (streaming) {
        auto now = std::chrono::steady_clock::now();
        currentStats.streamTimeMs = std::chrono::duration_cast<std::chrono::milliseconds>(
            now - startTime).count();
    }
    
    return currentStats;
}

void SrtStreamer::Impl::pushVideoFrame(const uint8_t* data, size_t size,
                                        int width, int height, int64_t timestampNs) {
#if GSTREAMER_AVAILABLE
    if (!streaming || !videoAppSrc) return;
    
    // Set caps dynamically on first frame or if resolution changes
    if (!videoCapsSet || width != lastVideoWidth || height != lastVideoHeight) {
        LOGI("Setting video caps: %dx%d @ %d fps", width, height, currentConfig.frameRate);
        
        GstCaps* caps = gst_caps_new_simple("video/x-raw",
            "format", G_TYPE_STRING, "NV21",
            "width", G_TYPE_INT, width,
            "height", G_TYPE_INT, height,
            "framerate", GST_TYPE_FRACTION, currentConfig.frameRate, 1,
            nullptr);
        
        g_object_set(videoAppSrc, "caps", caps, nullptr);
        gst_caps_unref(caps);
        
        lastVideoWidth = width;
        lastVideoHeight = height;
        videoCapsSet = true;
    }
    
    GstBuffer* buffer = gst_buffer_new_allocate(nullptr, size, nullptr);
    if (!buffer) {
        LOGE("Failed to allocate video buffer");
        return;
    }
    
    gst_buffer_fill(buffer, 0, data, size);
    // Let GStreamer assign timestamps via do-timestamp=true on appsrc
    // This ensures audio and video use the same pipeline clock
    GST_BUFFER_PTS(buffer) = GST_CLOCK_TIME_NONE;
    GST_BUFFER_DTS(buffer) = GST_CLOCK_TIME_NONE;
    GST_BUFFER_DURATION(buffer) = GST_SECOND / currentConfig.frameRate;
    
    GstFlowReturn ret = gst_app_src_push_buffer(GST_APP_SRC(videoAppSrc), buffer);
    if (ret != GST_FLOW_OK) {
        LOGE("Failed to push video frame: %d", ret);
    }
    
    // Update stats
    {
        std::lock_guard<std::mutex> lock(statsMutex);
        stats.bytesSent += size;
    }
#else
    // Stub: just track bytes
    std::lock_guard<std::mutex> lock(statsMutex);
    stats.bytesSent += size;
#endif
}

void SrtStreamer::Impl::pushAudioSamples(const uint8_t* data, size_t size,
                                          int sampleRate, int channels, int64_t timestampNs) {
#if GSTREAMER_AVAILABLE
    if (!streaming || !audioAppSrc) return;
    
    GstBuffer* buffer = gst_buffer_new_allocate(nullptr, size, nullptr);
    if (!buffer) {
        LOGE("Failed to allocate audio buffer");
        return;
    }
    
    gst_buffer_fill(buffer, 0, data, size);
    // Let GStreamer assign timestamps via do-timestamp=true on appsrc
    GST_BUFFER_PTS(buffer) = GST_CLOCK_TIME_NONE;
    GST_BUFFER_DTS(buffer) = GST_CLOCK_TIME_NONE;
    
    // Calculate duration based on sample count
    int bytesPerSample = 2 * channels;  // S16LE
    int sampleCount = size / bytesPerSample;
    GST_BUFFER_DURATION(buffer) = gst_util_uint64_scale(sampleCount, GST_SECOND, sampleRate);
    
    GstFlowReturn ret = gst_app_src_push_buffer(GST_APP_SRC(audioAppSrc), buffer);
    if (ret != GST_FLOW_OK) {
        LOGE("Failed to push audio samples: %d", ret);
    }
    
    // Update stats
    {
        std::lock_guard<std::mutex> lock(statsMutex);
        stats.bytesSent += size;
    }
#else
    std::lock_guard<std::mutex> lock(statsMutex);
    stats.bytesSent += size;
#endif
}

// SrtStreamer implementation (delegates to Impl)
SrtStreamer::SrtStreamer() : pImpl(std::make_unique<Impl>()) {}
SrtStreamer::~SrtStreamer() = default;

bool SrtStreamer::createPipeline(const StreamConfig& config) {
    return pImpl->createPipeline(config);
}

bool SrtStreamer::start() {
    return pImpl->start();
}

void SrtStreamer::stop() {
    pImpl->stop();
}

bool SrtStreamer::isStreaming() const {
    return pImpl->isStreaming();
}

StreamStats SrtStreamer::getStats() const {
    return pImpl->getStats();
}

void SrtStreamer::pushVideoFrame(const uint8_t* data, size_t size,
                                  int width, int height, int64_t timestampNs) {
    pImpl->pushVideoFrame(data, size, width, height, timestampNs);
}

void SrtStreamer::pushAudioSamples(const uint8_t* data, size_t size,
                                    int sampleRate, int channels, int64_t timestampNs) {
    pImpl->pushAudioSamples(data, size, sampleRate, channels, timestampNs);
}

void SrtStreamer::setStateCallback(StateCallback callback) {
    pImpl->stateCallback = std::move(callback);
}

void SrtStreamer::setStatsCallback(StatsCallback callback) {
    pImpl->statsCallback = std::move(callback);
}

void SrtStreamer::setErrorCallback(ErrorCallback callback) {
    pImpl->errorCallback = std::move(callback);
}

} // namespace orbistream

