package com.orbistream.streaming

import android.content.Context
import android.util.Log

/**
 * NativeStreamer provides the Kotlin interface to the native GStreamer SRT streaming pipeline.
 * 
 * This class handles:
 * - Native library loading
 * - Pipeline creation and lifecycle
 * - Pushing video frames and audio samples
 * - Streaming statistics
 */
object NativeStreamer {
    private const val TAG = "NativeStreamer"
    
    private var libraryLoaded = false
    private var gstreamerInitialized = false
    private var initialized = false

    /**
     * Callback interface for streaming events.
     */
    interface StreamCallback {
        fun onStateChanged(running: Boolean, message: String)
        fun onStatsUpdated(bitrate: Double, bytesSent: Long, packetsLost: Long, rtt: Double, streamTimeMs: Long)
        fun onError(error: String)
    }

    /**
     * Initialize GStreamer. Must be called from Application.onCreate() with context.
     */
    fun initGStreamer(context: Context): Boolean {
        if (gstreamerInitialized) return true
        
        return try {
            // Initialize GStreamer Android integration
            org.freedesktop.gstreamer.GStreamer.init(context)
            gstreamerInitialized = true
            Log.i(TAG, "GStreamer initialized successfully")
            
            // Now load our native library
            System.loadLibrary("gstreamer_android")
            System.loadLibrary("orbistream_native")
            libraryLoaded = true
            Log.i(TAG, "Native libraries loaded")
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "GStreamer initialization failed: ${e.message}")
            false
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library: ${e.message}")
            false
        }
    }

    /**
     * Initialize the native streaming engine.
     */
    fun initialize(): Boolean {
        if (!libraryLoaded) {
            Log.e(TAG, "Cannot initialize: native library not loaded. Call initGStreamer() first.")
            return false
        }
        
        if (initialized) {
            return true
        }

        return try {
            nativeInit()
            initialized = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "Initialization failed: ${e.message}")
            false
        }
    }

    /**
     * Set the callback for streaming events.
     */
    fun setCallback(callback: StreamCallback?) {
        if (!libraryLoaded) return
        nativeSetCallback(callback)
    }

    /**
     * Create the streaming pipeline.
     * 
     * @param config Streaming configuration
     * @return true if pipeline was created successfully
     */
    fun createPipeline(config: StreamConfig): Boolean {
        if (!initialized) {
            Log.e(TAG, "Cannot create pipeline: not initialized")
            return false
        }

        return nativeCreatePipeline(
            config.srtHost,
            config.srtPort,
            config.streamId,
            config.passphrase,
            config.videoWidth,
            config.videoHeight,
            config.videoBitrate,
            config.frameRate,
            config.audioBitrate,
            config.sampleRate,
            config.proxyHost,
            config.proxyPort,
            config.useProxy
        )
    }

    /**
     * Start streaming.
     */
    fun start(): Boolean {
        if (!initialized) {
            Log.e(TAG, "Cannot start: not initialized")
            return false
        }
        return nativeStart()
    }

    /**
     * Stop streaming.
     */
    fun stop() {
        if (initialized) {
            nativeStop()
        }
    }

    /**
     * Check if currently streaming.
     */
    fun isStreaming(): Boolean {
        return initialized && nativeIsStreaming()
    }

    /**
     * Push a video frame to the streaming pipeline.
     * 
     * @param data Frame data (NV21 format)
     * @param width Frame width
     * @param height Frame height
     * @param timestampNs Frame timestamp in nanoseconds
     */
    fun pushVideoFrame(data: ByteArray, width: Int, height: Int, timestampNs: Long) {
        if (isStreaming()) {
            nativePushVideoFrame(data, width, height, timestampNs)
        }
    }

    /**
     * Push audio samples to the streaming pipeline.
     * 
     * @param data Audio data (PCM S16LE)
     * @param sampleRate Sample rate
     * @param channels Number of channels
     * @param timestampNs Timestamp in nanoseconds
     */
    fun pushAudioSamples(data: ByteArray, sampleRate: Int, channels: Int, timestampNs: Long) {
        if (isStreaming()) {
            nativePushAudioSamples(data, sampleRate, channels, timestampNs)
        }
    }

    /**
     * Get current streaming statistics.
     * 
     * @return StreamStats or null if not streaming
     */
    fun getStats(): StreamStats? {
        if (!initialized) return null
        
        val stats = nativeGetStats() ?: return null
        if (stats.size < 5) return null
        
        return StreamStats(
            currentBitrate = stats[0],
            bytesSent = stats[1].toLong(),
            packetsLost = stats[2].toLong(),
            rtt = stats[3],
            streamTimeMs = stats[4].toLong()
        )
    }

    /**
     * Destroy the native streamer and free resources.
     */
    fun destroy() {
        if (initialized) {
            nativeDestroy()
            initialized = false
        }
    }

    /**
     * Check if the native library is available.
     */
    fun isAvailable(): Boolean = libraryLoaded

    // Native methods
    private external fun nativeInit()
    private external fun nativeSetCallback(callback: StreamCallback?)
    private external fun nativeCreatePipeline(
        srtHost: String,
        srtPort: Int,
        streamId: String?,
        passphrase: String?,
        videoWidth: Int,
        videoHeight: Int,
        videoBitrate: Int,
        frameRate: Int,
        audioBitrate: Int,
        sampleRate: Int,
        proxyHost: String?,
        proxyPort: Int,
        useProxy: Boolean
    ): Boolean
    private external fun nativeStart(): Boolean
    private external fun nativeStop()
    private external fun nativeIsStreaming(): Boolean
    private external fun nativePushVideoFrame(data: ByteArray, width: Int, height: Int, timestampNs: Long)
    private external fun nativePushAudioSamples(data: ByteArray, sampleRate: Int, channels: Int, timestampNs: Long)
    private external fun nativeGetStats(): DoubleArray?
    private external fun nativeDestroy()
}

/**
 * Streaming configuration.
 */
data class StreamConfig(
    val srtHost: String,
    val srtPort: Int = 9000,
    val streamId: String? = null,
    val passphrase: String? = null,
    val videoWidth: Int = 1920,
    val videoHeight: Int = 1080,
    val videoBitrate: Int = 4_000_000,
    val frameRate: Int = 30,
    val audioBitrate: Int = 128_000,
    val sampleRate: Int = 48000,
    val proxyHost: String? = "127.0.0.1",
    val proxyPort: Int = 28007,
    val useProxy: Boolean = true
)

/**
 * Streaming statistics.
 */
data class StreamStats(
    val currentBitrate: Double = 0.0,
    val bytesSent: Long = 0,
    val packetsLost: Long = 0,
    val rtt: Double = 0.0,
    val streamTimeMs: Long = 0
) {
    /**
     * Get bitrate in Mbps.
     */
    fun getBitrateMbps(): Double = currentBitrate / 1_000_000.0

    /**
     * Get stream duration formatted as HH:MM:SS.
     */
    fun getFormattedDuration(): String {
        val seconds = (streamTimeMs / 1000) % 60
        val minutes = (streamTimeMs / 60000) % 60
        val hours = streamTimeMs / 3600000
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }
}

