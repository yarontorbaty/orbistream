#include "srt_streamer.h"
#include <android/log.h>
#include <chrono>
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
    // Build the GStreamer pipeline string for SRT streaming
    // 
    // The pipeline uses appsrc for both video and audio so we can push
    // frames from the Android camera and microphone.
    //
    // Video path: appsrc -> videoconvert -> x264enc -> h264parse
    // Audio path: appsrc -> audioconvert -> voaacenc -> aacparse
    // Both paths mux into mpegtsmux -> srtsink

    std::string srtUri = "srt://" + config.srtHost + ":" + std::to_string(config.srtPort);
    if (!config.streamId.empty()) {
        srtUri += "?streamid=" + config.streamId;
    }

    LOGI("=== SRT CONNECTION CONFIG ===");
    LOGI("SRT URI: %s", srtUri.c_str());
    LOGI("Video: %dx%d @ %d fps, bitrate %d bps", 
         config.videoWidth, config.videoHeight, config.frameRate, config.videoBitrate);
    LOGI("Audio: %d Hz, bitrate %d bps", config.sampleRate, config.audioBitrate);
    if (config.useProxy) {
        LOGI("Proxy: %s:%d (Bondix)", config.proxyHost.c_str(), config.proxyPort);
    }
    LOGI("=============================");

    // Configure SRT sink with proxy if enabled
    std::string srtSinkProps = "uri=\"" + srtUri + "\" latency=500";
    
    // Note: GStreamer's srtsink might not directly support SOCKS5.
    // For Bondix integration, we rely on the JVM-level proxy settings
    // which affect Java-based network connections. For native SRT,
    // Bondix binds the sockets directly via the SocketBindCallback.
    
    if (!config.passphrase.empty()) {
        srtSinkProps += " passphrase=\"" + config.passphrase + "\"";
    }

    std::stringstream ss;
    
    // Video source from app - accept any size, we'll scale to target
    ss << "appsrc name=video_src format=time is-live=true do-timestamp=true "
       << "caps=\"video/x-raw,format=NV21,framerate=" << config.frameRate << "/1\" ! ";
    
    // Video processing: convert, scale to target size, encode
    ss << "videoconvert ! "
       << "videoscale ! video/x-raw,width=" << config.videoWidth << ",height=" << config.videoHeight << " ! "
       << "x264enc tune=zerolatency bitrate=" << (config.videoBitrate / 1000) 
       << " speed-preset=superfast key-int-max=" << (config.frameRate * 2) << " ! "
       << "h264parse ! queue name=video_queue ! mux. ";
    
    // Audio source from app - must include layout=interleaved for audioconvert
    ss << "appsrc name=audio_src format=time is-live=true do-timestamp=true "
       << "caps=\"audio/x-raw,format=S16LE,layout=interleaved,rate=" << config.sampleRate 
       << ",channels=" << config.audioChannels << "\" ! ";
    
    // Audio encoding
    ss << "audioconvert ! "
       << "voaacenc bitrate=" << config.audioBitrate << " ! "
       << "aacparse ! queue name=audio_queue ! mux. ";
    
    // Muxer and SRT output
    ss << "mpegtsmux name=mux ! "
       << "srtsink name=srt_sink " << srtSinkProps;
    
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
    
    if (!videoAppSrc || !audioAppSrc) {
        LOGE("Failed to get appsrc elements");
        cleanup();
        return false;
    }
    
    // Configure appsrc for streaming
    g_object_set(videoAppSrc,
        "stream-type", 0,  // GST_APP_STREAM_TYPE_STREAM
        "format", GST_FORMAT_TIME,
        nullptr);
    
    g_object_set(audioAppSrc,
        "stream-type", 0,
        "format", GST_FORMAT_TIME,
        nullptr);
    
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
    
    GstBuffer* buffer = gst_buffer_new_allocate(nullptr, size, nullptr);
    if (!buffer) {
        LOGE("Failed to allocate video buffer");
        return;
    }
    
    gst_buffer_fill(buffer, 0, data, size);
    GST_BUFFER_PTS(buffer) = timestampNs;
    GST_BUFFER_DTS(buffer) = timestampNs;
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
    GST_BUFFER_PTS(buffer) = timestampNs;
    GST_BUFFER_DTS(buffer) = timestampNs;
    
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

