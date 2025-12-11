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
    void updateSrtStats();
    void updateAdaptiveBitrate();
    static const char* presetToString(EncoderPreset preset);
    
#if GSTREAMER_AVAILABLE
    GstElement* pipeline = nullptr;
    GstElement* videoAppSrc = nullptr;
    GstElement* audioAppSrc = nullptr;
    GstElement* srtSink = nullptr;
    GstElement* udpSink = nullptr;
    GstElement* muxer = nullptr;
    GstElement* videoEncoder = nullptr;
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
    int64_t lastBytesSent = 0;
    std::chrono::steady_clock::time_point lastBitrateTime;
    
    // Byte counting from muxer (fallback when sink stats aren't available)
    std::atomic<uint64_t> muxerBytesSent{0};
    
    // Adaptive bitrate
    int currentEncoderBitrate = 0;    // Current encoder bitrate in kbps
    int targetBitrate = 0;            // Target bitrate in kbps
    int minBitrate = 500;             // Minimum bitrate in kbps
    int maxBitrate = 0;               // Maximum bitrate in kbps (from config)
    std::chrono::steady_clock::time_point lastBitrateAdjustTime;
};

// Helper to convert EncoderPreset to x264 string
const char* SrtStreamer::Impl::presetToString(EncoderPreset preset) {
    switch (preset) {
        case EncoderPreset::ULTRAFAST: return "ultrafast";
        case EncoderPreset::SUPERFAST: return "superfast";
        case EncoderPreset::VERYFAST: return "veryfast";
        case EncoderPreset::FASTER: return "faster";
        case EncoderPreset::FAST: return "fast";
        case EncoderPreset::MEDIUM: return "medium";
        case EncoderPreset::SLOW: return "slow";
        case EncoderPreset::SLOWER: return "slower";
        case EncoderPreset::VERYSLOW: return "veryslow";
        default: return "ultrafast";
    }
}

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
    
    const char* presetStr = presetToString(config.preset);
    int gopSize = config.frameRate * config.keyframeInterval;
    
    LOGI("=== STREAMING CONFIG ===");
    LOGI("Transport: %s", transportStr);
    LOGI("Target: %s:%d", config.srtHost.c_str(), config.srtPort);
    LOGI("Video: %dx%d @ %d fps, bitrate %d bps", 
         config.videoWidth, config.videoHeight, config.frameRate, config.videoBitrate);
    LOGI("Encoder: preset=%s, keyframe=%ds (GOP=%d), bframes=%d",
         presetStr, config.keyframeInterval, gopSize, config.bFrames);
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
    // - x264enc with configurable preset and GOP
    // - Direct to mux (no h264parse - MCRBox doesn't use it)
    ss << "videorate drop-only=true skip-to-first=true ! "
       << "videoconvert ! "
       << "videoscale ! video/x-raw,width=" << config.videoWidth << ",height=" << config.videoHeight << " ! "
       << "x264enc name=video_enc tune=zerolatency speed-preset=" << presetStr
       << " bitrate=" << (config.videoBitrate / 1000)
       << " key-int-max=" << gopSize  // GOP = frameRate * keyframeInterval
       << " bframes=" << config.bFrames
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
    
    // Get video encoder for adaptive bitrate and probes
    videoEncoder = gst_bin_get_by_name(GST_BIN(pipeline), "video_enc");
    if (videoEncoder) {
        LOGI("Got video encoder for adaptive bitrate");
        // Initialize adaptive bitrate settings
        currentEncoderBitrate = config.videoBitrate / 1000;  // kbps
        targetBitrate = currentEncoderBitrate;
        maxBitrate = currentEncoderBitrate;
        minBitrate = std::max(500, maxBitrate / 10);  // Min 500kbps or 10% of max
    }
    
    // Get sink elements for stats
    if (config.transport == TransportMode::SRT) {
        srtSink = gst_bin_get_by_name(GST_BIN(pipeline), "srt_sink");
        if (srtSink) {
            LOGI("Got SRT sink for stats collection");
        }
    } else {
        udpSink = gst_bin_get_by_name(GST_BIN(pipeline), "udp_sink");
        if (udpSink) {
            LOGI("Got UDP sink for stats collection");
        }
    }
    
    // Get muxer for byte counting (works for both SRT and UDP)
    muxer = gst_bin_get_by_name(GST_BIN(pipeline), "mux");
    if (muxer) {
        // Note: Muxer probe removed - it doesn't output until BOTH audio+video flow
        // Byte counting now happens on h264 encoder output probe below
        LOGI("Got muxer element");
    }
    
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

    // Pad probe on x264enc src to:
    // 1. Count encoded video bytes for bitrate calculation
    // 2. Inspect NAL headers for SPS/PPS/IDR presence
    if (videoEncoder) {
        GstPad* encSrc = gst_element_get_static_pad(videoEncoder, "src");
        if (encSrc) {
            gst_pad_add_probe(encSrc, GST_PAD_PROBE_TYPE_BUFFER,
                [](GstPad*, GstPadProbeInfo* info, gpointer user_data) -> GstPadProbeReturn {
                    auto* counter = static_cast<std::atomic<uint64_t>*>(user_data);
                    GstBuffer* buf = GST_PAD_PROBE_INFO_BUFFER(info);
                    if (!buf) return GST_PAD_PROBE_OK;
                    
                    // Count encoded bytes
                    gsize bufSize = gst_buffer_get_size(buf);
                    if (counter) {
                        counter->fetch_add(bufSize, std::memory_order_relaxed);
                    }
                    
                    // Debug logging for first 10 buffers
                    static int logged = 0;
                    if (logged >= 10) return GST_PAD_PROBE_OK;
                    
                    GstMapInfo map;
                    if (!gst_buffer_map(buf, &map, GST_MAP_READ))
                        return GST_PAD_PROBE_OK;
                    
                    // Scan buffer for NAL types
                    bool hasIDR = false, hasSPS = false, hasPPS = false;
                    std::vector<int> nalTypes;
                    for (size_t i = 0; i + 4 < map.size; ++i) {
                        if (map.data[i] == 0x00 && map.data[i+1] == 0x00) {
                            size_t hdr = 0;
                            if (map.data[i+2] == 0x01) {
                                hdr = i + 3;
                            } else if (map.data[i+2] == 0x00 && i + 4 < map.size && map.data[i+3] == 0x01) {
                                hdr = i + 4;
                            }
                            if (hdr > 0 && hdr < map.size) {
                                int nt = map.data[hdr] & 0x1F;
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
                    
                    LOGI("h264probe buf=%zu nal_types=[%s] IDR=%d SPS=%d PPS=%d total=%llu", 
                         map.size, nalList.str().c_str(), hasIDR, hasSPS, hasPPS,
                         counter ? (unsigned long long)counter->load() : 0ULL);
                    
                    gst_buffer_unmap(buf, &map);
                    logged++;
                    return GST_PAD_PROBE_OK;
                },
                &muxerBytesSent, nullptr);  // Reuse muxerBytesSent for h264 bytes
            gst_object_unref(encSrc);
            LOGI("Added h264 byte counting probe on video encoder");
        }
        // Note: videoEncoder is unreffed in cleanup()
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
    lastBitrateTime = startTime;
    lastBitrateAdjustTime = startTime;
    lastBytesSent = 0;
    muxerBytesSent = 0;
    
    // Set initial connection state
    {
        std::lock_guard<std::mutex> lock(statsMutex);
        stats.connectionState = SrtConnectionState::CONNECTING;
    }
    
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
    LOGI("Total bytes sent (SRT): %llu", (unsigned long long)stats.bytesSent);
    LOGI("Total bytes sent (muxer): %llu", (unsigned long long)muxerBytesSent.load());
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
    if (srtSink) {
        gst_object_unref(srtSink);
        srtSink = nullptr;
    }
    if (udpSink) {
        gst_object_unref(udpSink);
        udpSink = nullptr;
    }
    if (muxer) {
        gst_object_unref(muxer);
        muxer = nullptr;
    }
    if (videoEncoder) {
        gst_object_unref(videoEncoder);
        videoEncoder = nullptr;
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

void SrtStreamer::Impl::updateSrtStats() {
#if GSTREAMER_AVAILABLE
    if (!streaming) return;
    
    std::lock_guard<std::mutex> lock(statsMutex);
    auto now = std::chrono::steady_clock::now();
    auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(now - lastBitrateTime).count();
    
    if (srtSink) {
        // SRT mode: Query actual statistics from srtsink
        GstStructure* srtStats = nullptr;
        g_object_get(srtSink, "stats", &srtStats, nullptr);
        
        if (srtStats) {
            // Log structure fields once for debugging
            static bool loggedFields = false;
            if (!loggedFields) {
                gchar* str = gst_structure_to_string(srtStats);
                LOGI("SRT stats structure: %s", str);
                g_free(str);
                loggedFields = true;
            }
            
            // Try multiple field name formats (SRT stats vary by version)
            gint64 byteSentTotal = 0;
            gint64 pktSentTotal = 0;
            gint64 pktSentLoss = 0;
            gint64 pktRetrans = 0;
            gint64 pktSndDrop = 0;
            gdouble msRTT = 0.0;
            gint64 mbpsSendRate = 0;
            gint64 mbpsBandwidth = 0;
            
            // Bytes sent - try various field names
            if (!gst_structure_get_int64(srtStats, "bytes-sent-total", &byteSentTotal)) {
                if (!gst_structure_get_int64(srtStats, "bytes-sent", &byteSentTotal)) {
                    gst_structure_get_int64(srtStats, "bytesSentTotal", &byteSentTotal);
                }
            }
            
            // Packets sent
            if (!gst_structure_get_int64(srtStats, "packets-sent", &pktSentTotal)) {
                gst_structure_get_int64(srtStats, "pktSent", &pktSentTotal);
            }
            
            // Packets lost
            if (!gst_structure_get_int64(srtStats, "packets-sent-lost", &pktSentLoss)) {
                gst_structure_get_int64(srtStats, "pktSndLoss", &pktSentLoss);
            }
            
            // Retransmits
            if (!gst_structure_get_int64(srtStats, "packets-retransmitted", &pktRetrans)) {
                gst_structure_get_int64(srtStats, "pktRetrans", &pktRetrans);
            }
            
            // Dropped
            if (!gst_structure_get_int64(srtStats, "packets-sent-dropped", &pktSndDrop)) {
                gst_structure_get_int64(srtStats, "pktSndDrop", &pktSndDrop);
            }
            
            // RTT
            if (!gst_structure_get_double(srtStats, "rtt-ms", &msRTT)) {
                gst_structure_get_double(srtStats, "msRTT", &msRTT);
            }
            
            // Send rate (actual current sending rate)
            if (!gst_structure_get_int64(srtStats, "send-rate-mbps", &mbpsSendRate)) {
                gst_structure_get_int64(srtStats, "mbpsSendRate", &mbpsSendRate);
            }
            
            // Bandwidth estimate (SRT's estimate of available bandwidth)
            if (!gst_structure_get_int64(srtStats, "bandwidth-mbps", &mbpsBandwidth)) {
                gst_structure_get_int64(srtStats, "mbpsBandwidth", &mbpsBandwidth);
            }
            
            stats.packetsLost = static_cast<uint64_t>(pktSentLoss);
            stats.packetsRetransmitted = static_cast<uint64_t>(pktRetrans);
            stats.packetsDropped = static_cast<uint64_t>(pktSndDrop);
            stats.rtt = msRTT;
            stats.bandwidth = mbpsBandwidth * 1000000;  // Convert Mbps to bps
            
            // Update bytesSent - prefer SRT stats, fallback to muxer count
            if (byteSentTotal > 0) {
                stats.bytesSent = static_cast<uint64_t>(byteSentTotal);
            } else {
                // Fallback to muxer byte counting
                stats.bytesSent = muxerBytesSent.load(std::memory_order_relaxed);
            }
            
            // Calculate bitrate from bytes sent over time
            if (elapsed >= 1000 && stats.bytesSent > 0) {
                int64_t byteDiff = stats.bytesSent - lastBytesSent;
                if (byteDiff > 0) {
                    stats.currentBitrate = (byteDiff * 8.0 * 1000.0) / elapsed;  // bps
                }
                lastBytesSent = stats.bytesSent;
                lastBitrateTime = now;
            }
            
            // Use SRT's send rate if available and we haven't calculated yet
            if (mbpsSendRate > 0 && stats.currentBitrate == 0) {
                stats.currentBitrate = mbpsSendRate * 1000000.0;  // Mbps to bps
            }
            
            // Connection state - check if bytes are actually flowing
            if (stats.bytesSent > 0) {
                stats.connectionState = SrtConnectionState::CONNECTED;
            } else if (pktSentTotal > 0) {
                stats.connectionState = SrtConnectionState::CONNECTED;
            }
            
            gst_structure_free(srtStats);
            
            // Run adaptive bitrate adjustment
            updateAdaptiveBitrate();
        } else {
            LOGD("SRT sink has no stats available yet");
        }
    } else {
        // UDP mode: Use muxer byte counting for actual transmitted data
        stats.bytesSent = muxerBytesSent.load(std::memory_order_relaxed);
        
        // Calculate bitrate from bytes sent over time
        if (elapsed >= 1000 && stats.bytesSent > 0) {
            int64_t byteDiff = stats.bytesSent - lastBytesSent;
            if (byteDiff > 0) {
                stats.currentBitrate = (byteDiff * 8.0 * 1000.0) / elapsed;  // bps
            }
            lastBytesSent = stats.bytesSent;
            lastBitrateTime = now;
        }
        
        // If we haven't calculated bitrate yet, use configured value
        if (stats.currentBitrate == 0) {
            stats.currentBitrate = currentConfig.videoBitrate + currentConfig.audioBitrate;
        }
        
        stats.connectionState = SrtConnectionState::CONNECTED;  // UDP is "connectionless"
        stats.rtt = 0;
        stats.packetsLost = 0;
        stats.packetsRetransmitted = 0;
        stats.packetsDropped = 0;
    }
#endif
}

void SrtStreamer::Impl::updateAdaptiveBitrate() {
#if GSTREAMER_AVAILABLE
    if (!videoEncoder || !streaming) return;
    
    auto now = std::chrono::steady_clock::now();
    auto timeSinceLastAdjust = std::chrono::duration_cast<std::chrono::milliseconds>(
        now - lastBitrateAdjustTime).count();
    
    // Only adjust every 2 seconds to avoid oscillation
    if (timeSinceLastAdjust < 2000) return;
    
    // Get current stats (already locked by caller)
    double lossRate = 0.0;
    if (stats.bytesSent > 0) {
        // Calculate loss rate as percentage
        // Using packets sent estimate (bytes / 1316 typical SRT packet size)
        uint64_t estPacketsSent = stats.bytesSent / 1316;
        if (estPacketsSent > 0) {
            lossRate = (stats.packetsLost * 100.0) / (estPacketsSent + stats.packetsLost);
        }
    }
    
    // Adaptive bitrate logic:
    // 1. High loss (>5%) or high RTT (>500ms) -> reduce bitrate aggressively
    // 2. Moderate loss (1-5%) -> reduce bitrate slowly
    // 3. Low loss (<1%) and low RTT -> increase bitrate slowly toward max
    
    int newBitrate = currentEncoderBitrate;
    
    if (lossRate > 5.0 || stats.rtt > 500.0) {
        // Aggressive reduction: drop by 30%
        newBitrate = currentEncoderBitrate * 70 / 100;
        LOGI("ABR: High loss/RTT (loss=%.1f%%, rtt=%.0fms) -> reduce to %d kbps", 
             lossRate, stats.rtt, newBitrate);
    } else if (lossRate > 1.0 || stats.rtt > 200.0) {
        // Slow reduction: drop by 10%
        newBitrate = currentEncoderBitrate * 90 / 100;
        LOGI("ABR: Moderate loss/RTT (loss=%.1f%%, rtt=%.0fms) -> reduce to %d kbps", 
             lossRate, stats.rtt, newBitrate);
    } else if (lossRate < 0.5 && stats.rtt < 100.0 && currentEncoderBitrate < maxBitrate) {
        // Network is good, increase by 10% toward max
        newBitrate = std::min(maxBitrate, currentEncoderBitrate * 110 / 100);
        LOGI("ABR: Good conditions (loss=%.1f%%, rtt=%.0fms) -> increase to %d kbps", 
             lossRate, stats.rtt, newBitrate);
    }
    
    // Also consider SRT's bandwidth estimate if available
    if (stats.bandwidth > 0) {
        int bwBitrate = static_cast<int>(stats.bandwidth / 1000);  // bps to kbps
        // Use 80% of estimated bandwidth as ceiling
        int bwCeiling = bwBitrate * 80 / 100;
        if (bwCeiling < newBitrate) {
            newBitrate = bwCeiling;
            LOGI("ABR: Bandwidth limited to %d kbps (SRT estimate: %d kbps)", 
                 newBitrate, bwBitrate);
        }
    }
    
    // Clamp to min/max
    newBitrate = std::max(minBitrate, std::min(maxBitrate, newBitrate));
    
    // Only apply if change is significant (>5%)
    int diff = abs(newBitrate - currentEncoderBitrate);
    if (diff > currentEncoderBitrate / 20) {
        LOGI("ABR: Adjusting bitrate: %d -> %d kbps", currentEncoderBitrate, newBitrate);
        g_object_set(videoEncoder, "bitrate", newBitrate, nullptr);
        currentEncoderBitrate = newBitrate;
        lastBitrateAdjustTime = now;
    }
#endif
}

StreamStats SrtStreamer::Impl::getStats() const {
    // Need to call updateSrtStats which modifies state, so cast away const
    const_cast<SrtStreamer::Impl*>(this)->updateSrtStats();
    
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
    
    // Note: Don't count raw frame bytes here - they're uncompressed.
    // Actual bytes sent are tracked via sink stats (SRT) or estimated from bitrate.
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
    
    // Note: Don't count raw audio bytes here - they're uncompressed PCM.
    // Actual bytes sent are tracked via sink stats (SRT) or estimated from bitrate.
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

