#include <jni.h>
#include <android/log.h>
#include <memory>
#include "srt_streamer.h"

#if GSTREAMER_AVAILABLE
#include <gst/gst.h>
#endif

#define LOG_TAG "OrbiStreamJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace orbistream;

// Global streamer instance
static std::unique_ptr<SrtStreamer> g_streamer;
static JavaVM* g_jvm = nullptr;
static jobject g_callbackObject = nullptr;
static jmethodID g_onStateChanged = nullptr;
static jmethodID g_onStatsUpdated = nullptr;
static jmethodID g_onError = nullptr;
static bool g_gstreamer_initialized = false;

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    LOGI("JNI_OnLoad: liborbistream_native loaded");
    return JNI_VERSION_1_6;
}

/**
 * GStreamer JNI nativeInit - called from org.freedesktop.gstreamer.GStreamer.init()
 * This initializes the GStreamer framework on Android.
 */
JNIEXPORT void JNICALL
Java_org_freedesktop_gstreamer_GStreamer_nativeInit(JNIEnv* env, jclass clazz, jobject context) {
    LOGI("=== GStreamer nativeInit called ===");
    
#if GSTREAMER_AVAILABLE
    if (g_gstreamer_initialized) {
        LOGI("GStreamer already initialized");
        return;
    }
    
    // Get the files directory path from Android context
    jclass contextClass = env->GetObjectClass(context);
    jmethodID getFilesDir = env->GetMethodID(contextClass, "getFilesDir", "()Ljava/io/File;");
    jobject filesDir = env->CallObjectMethod(context, getFilesDir);
    
    jclass fileClass = env->FindClass("java/io/File");
    jmethodID getAbsolutePath = env->GetMethodID(fileClass, "getAbsolutePath", "()Ljava/lang/String;");
    jstring pathString = (jstring)env->CallObjectMethod(filesDir, getAbsolutePath);
    
    const char* filesPath = env->GetStringUTFChars(pathString, nullptr);
    
    // Set environment variables for GStreamer
    std::string fontConfig = std::string(filesPath) + "/fontconfig/fonts.conf";
    std::string caCerts = std::string(filesPath) + "/ssl/certs/ca-certificates.crt";
    
    setenv("FONTCONFIG_FILE", fontConfig.c_str(), 1);
    setenv("CA_CERTIFICATES", caCerts.c_str(), 1);
    setenv("HOME", filesPath, 1);
    
    LOGI("GStreamer paths: FONTCONFIG_FILE=%s", fontConfig.c_str());
    LOGI("GStreamer paths: CA_CERTIFICATES=%s", caCerts.c_str());
    
    env->ReleaseStringUTFChars(pathString, filesPath);
    
    // Initialize GStreamer
    GError* error = nullptr;
    if (!gst_init_check(nullptr, nullptr, &error)) {
        if (error) {
            LOGE("GStreamer init failed: %s", error->message);
            g_error_free(error);
        } else {
            LOGE("GStreamer init failed: unknown error");
        }
        return;
    }
    
    g_gstreamer_initialized = true;
    LOGI("=== GStreamer initialized successfully ===");
    LOGI("GStreamer version: %s", gst_version_string());
#else
    LOGE("GStreamer not available - built without GSTREAMER_AVAILABLE");
#endif
}

JNIEXPORT void JNICALL
Java_com_orbistream_streaming_NativeStreamer_nativeInit(JNIEnv* env, jclass clazz) {
    LOGI("Initializing native streamer");
    SrtStreamer::initGStreamer();
    g_streamer = std::make_unique<SrtStreamer>();
    
    // Set up callbacks
    g_streamer->setStateCallback([](bool running, const std::string& message) {
        if (!g_jvm || !g_callbackObject || !g_onStateChanged) return;
        
        JNIEnv* env = nullptr;
        bool attached = false;
        
        if (g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
            if (g_jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                attached = true;
            } else {
                return;
            }
        }
        
        jstring jMessage = env->NewStringUTF(message.c_str());
        env->CallVoidMethod(g_callbackObject, g_onStateChanged, running, jMessage);
        env->DeleteLocalRef(jMessage);
        
        if (attached) {
            g_jvm->DetachCurrentThread();
        }
    });
    
    g_streamer->setErrorCallback([](const std::string& error) {
        if (!g_jvm || !g_callbackObject || !g_onError) return;
        
        JNIEnv* env = nullptr;
        bool attached = false;
        
        if (g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
            if (g_jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                attached = true;
            } else {
                return;
            }
        }
        
        jstring jError = env->NewStringUTF(error.c_str());
        env->CallVoidMethod(g_callbackObject, g_onError, jError);
        env->DeleteLocalRef(jError);
        
        if (attached) {
            g_jvm->DetachCurrentThread();
        }
    });
}

JNIEXPORT void JNICALL
Java_com_orbistream_streaming_NativeStreamer_nativeSetCallback(
        JNIEnv* env, jclass clazz, jobject callback) {
    // Clean up previous callback
    if (g_callbackObject) {
        env->DeleteGlobalRef(g_callbackObject);
        g_callbackObject = nullptr;
    }
    
    if (callback) {
        g_callbackObject = env->NewGlobalRef(callback);
        
        jclass callbackClass = env->GetObjectClass(callback);
        g_onStateChanged = env->GetMethodID(callbackClass, "onStateChanged", "(ZLjava/lang/String;)V");
        g_onStatsUpdated = env->GetMethodID(callbackClass, "onStatsUpdated", "(DJJDJ)V");
        g_onError = env->GetMethodID(callbackClass, "onError", "(Ljava/lang/String;)V");
        
        LOGI("Callback set successfully");
    }
}

JNIEXPORT jboolean JNICALL
Java_com_orbistream_streaming_NativeStreamer_nativeCreatePipeline(
        JNIEnv* env, jclass clazz,
        jstring srtHost, jint srtPort, jstring streamId, jstring passphrase,
        jint videoWidth, jint videoHeight, jint videoBitrate, jint frameRate,
        jint audioBitrate, jint sampleRate,
        jstring proxyHost, jint proxyPort, jboolean useProxy,
        jint transportMode,
        jint encoderPreset, jint keyframeInterval, jint bFrames) {
    
    if (!g_streamer) {
        LOGE("Streamer not initialized");
        return JNI_FALSE;
    }
    
    StreamConfig config;
    
    // Parse transport mode: 0 = UDP, 1 = SRT
    config.transport = (transportMode == 0) ? TransportMode::UDP : TransportMode::SRT;
    
    // Parse strings
    const char* host = env->GetStringUTFChars(srtHost, nullptr);
    config.srtHost = host;
    env->ReleaseStringUTFChars(srtHost, host);
    
    config.srtPort = srtPort;
    
    if (streamId) {
        const char* id = env->GetStringUTFChars(streamId, nullptr);
        config.streamId = id;
        env->ReleaseStringUTFChars(streamId, id);
    }
    
    if (passphrase) {
        const char* pass = env->GetStringUTFChars(passphrase, nullptr);
        config.passphrase = pass;
        env->ReleaseStringUTFChars(passphrase, pass);
    }
    
    config.videoWidth = videoWidth;
    config.videoHeight = videoHeight;
    config.videoBitrate = videoBitrate;
    config.frameRate = frameRate;
    config.audioBitrate = audioBitrate;
    config.sampleRate = sampleRate;
    
    // Encoder settings
    config.preset = static_cast<EncoderPreset>(encoderPreset);
    config.keyframeInterval = keyframeInterval;
    config.bFrames = bFrames;
    
    if (proxyHost) {
        const char* pHost = env->GetStringUTFChars(proxyHost, nullptr);
        config.proxyHost = pHost;
        env->ReleaseStringUTFChars(proxyHost, pHost);
    }
    config.proxyPort = proxyPort;
    config.useProxy = useProxy;
    
    const char* transportStr = (config.transport == TransportMode::UDP) ? "UDP" : "SRT";
    LOGI("Creating pipeline [%s]: %s:%d, video %dx%d@%d, bitrate %d, preset=%d, keyframe=%d, bframes=%d",
         transportStr, config.srtHost.c_str(), config.srtPort,
         config.videoWidth, config.videoHeight, config.frameRate, config.videoBitrate,
         encoderPreset, keyframeInterval, bFrames);
    
    return g_streamer->createPipeline(config) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_orbistream_streaming_NativeStreamer_nativeStart(JNIEnv* env, jclass clazz) {
    if (!g_streamer) {
        LOGE("Streamer not initialized");
        return JNI_FALSE;
    }
    return g_streamer->start() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_orbistream_streaming_NativeStreamer_nativeStop(JNIEnv* env, jclass clazz) {
    if (g_streamer) {
        g_streamer->stop();
    }
}

JNIEXPORT jboolean JNICALL
Java_com_orbistream_streaming_NativeStreamer_nativeIsStreaming(JNIEnv* env, jclass clazz) {
    return (g_streamer && g_streamer->isStreaming()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_orbistream_streaming_NativeStreamer_nativePushVideoFrame(
        JNIEnv* env, jclass clazz,
        jbyteArray data, jint width, jint height, jlong timestampNs) {
    
    if (!g_streamer || !g_streamer->isStreaming()) return;
    
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    jsize size = env->GetArrayLength(data);
    
    g_streamer->pushVideoFrame(
        reinterpret_cast<const uint8_t*>(bytes), size,
        width, height, timestampNs);
    
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_orbistream_streaming_NativeStreamer_nativePushAudioSamples(
        JNIEnv* env, jclass clazz,
        jbyteArray data, jint sampleRate, jint channels, jlong timestampNs) {
    
    if (!g_streamer || !g_streamer->isStreaming()) return;
    
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    jsize size = env->GetArrayLength(data);
    
    g_streamer->pushAudioSamples(
        reinterpret_cast<const uint8_t*>(bytes), size,
        sampleRate, channels, timestampNs);
    
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
}

JNIEXPORT jdoubleArray JNICALL
Java_com_orbistream_streaming_NativeStreamer_nativeGetStats(JNIEnv* env, jclass clazz) {
    if (!g_streamer) {
        return nullptr;
    }
    
    StreamStats stats = g_streamer->getStats();
    
    // Extended stats array:
    // [0] currentBitrate, [1] bytesSent, [2] packetsLost, [3] rtt, [4] streamTimeMs,
    // [5] packetsRetransmitted, [6] packetsDropped, [7] bandwidth, [8] connectionState
    jdoubleArray result = env->NewDoubleArray(9);
    jdouble values[9] = {
        stats.currentBitrate,
        static_cast<double>(stats.bytesSent),
        static_cast<double>(stats.packetsLost),
        stats.rtt,
        static_cast<double>(stats.streamTimeMs),
        static_cast<double>(stats.packetsRetransmitted),
        static_cast<double>(stats.packetsDropped),
        static_cast<double>(stats.bandwidth),
        static_cast<double>(static_cast<int>(stats.connectionState))
    };
    env->SetDoubleArrayRegion(result, 0, 9, values);
    
    return result;
}

JNIEXPORT void JNICALL
Java_com_orbistream_streaming_NativeStreamer_nativeDestroy(JNIEnv* env, jclass clazz) {
    LOGI("Destroying native streamer");
    
    if (g_callbackObject) {
        env->DeleteGlobalRef(g_callbackObject);
        g_callbackObject = nullptr;
    }
    
    g_streamer.reset();
}

} // extern "C"

