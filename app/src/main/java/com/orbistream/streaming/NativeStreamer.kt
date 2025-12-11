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
     * This loads all native libraries and initializes the GStreamer framework.
     */
    fun initGStreamer(context: Context): Boolean {
        if (gstreamerInitialized) return true
        
        return try {
            // GStreamer.init() loads both libgstreamer_android.so and liborbistream_native.so,
            // then calls our native Java_org_freedesktop_gstreamer_GStreamer_nativeInit()
            // which initializes GStreamer via gst_init()
            org.freedesktop.gstreamer.GStreamer.init(context)
            
            gstreamerInitialized = true
            libraryLoaded = true
            Log.i(TAG, "GStreamer initialized successfully")
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "GStreamer initialization failed: ${e.message}")
            e.printStackTrace()
            false
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library: ${e.message}")
            e.printStackTrace()
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

        val transportName = if (config.transport == TransportMode.UDP) "UDP" else "SRT"
        val protocol = if (config.transport == TransportMode.UDP) "udp" else "srt"
        
        Log.i(TAG, "=== Creating $transportName Pipeline ===")
        Log.i(TAG, "Target: $protocol://${config.srtHost}:${config.srtPort}")
        Log.i(TAG, "Stream ID: ${config.streamId ?: "(none)"}")
        Log.i(TAG, "Video: ${config.videoWidth}x${config.videoHeight} @ ${config.frameRate}fps, ${config.videoBitrate/1000}kbps")
        Log.i(TAG, "Audio: ${config.sampleRate}Hz, ${config.audioBitrate/1000}kbps")
        if (config.transport == TransportMode.UDP && config.useProxy) {
            Log.i(TAG, "Bondix: Enabled - reliability via bonded tunnel")
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
            config.useProxy,
            config.transport.value,
            config.encoderPreset.value,
            config.keyframeInterval,
            config.bFrames
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
        Log.i(TAG, "=== Starting SRT Stream ===")
        val result = nativeStart()
        if (result) {
            Log.i(TAG, "Stream started successfully")
        } else {
            Log.e(TAG, "!!! Stream failed to start !!!")
        }
        return result
    }

    /**
     * Stop streaming.
     */
    fun stop() {
        if (initialized) {
            Log.i(TAG, "=== Stopping SRT Stream ===")
            nativeStop()
            Log.i(TAG, "Stream stopped")
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
        if (stats.size < 9) return null
        
        return StreamStats(
            currentBitrate = stats[0],
            bytesSent = stats[1].toLong(),
            packetsLost = stats[2].toLong(),
            rtt = stats[3],
            streamTimeMs = stats[4].toLong(),
            packetsRetransmitted = stats[5].toLong(),
            packetsDropped = stats[6].toLong(),
            bandwidth = stats[7].toLong(),
            connectionState = SrtConnectionState.fromOrdinal(stats[8].toInt())
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
        useProxy: Boolean,
        transportMode: Int,     // 0 = UDP, 1 = SRT
        encoderPreset: Int,     // 0 = ultrafast ... 8 = veryslow
        keyframeInterval: Int,  // Keyframe every N seconds
        bFrames: Int            // B-frames (0 for low latency)
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
 * Transport mode for streaming.
 * 
 * - UDP: Plain UDP MPEG-TS (use with Bondix - Bondix provides reliability)
 * - SRT: SRT protocol with built-in retransmission (use when NOT using Bondix)
 */
enum class TransportMode(val value: Int) {
    UDP(0),  // Plain UDP - relies on Bondix for reliability
    SRT(1)   // SRT protocol - has its own retransmission
}

/**
 * Encoder presets (maps to x264 speed-preset).
 */
enum class EncoderPreset(val value: Int) {
    ULTRAFAST(0),   // Fastest, lowest quality
    SUPERFAST(1),
    VERYFAST(2),
    FASTER(3),
    FAST(4),
    MEDIUM(5),      // Default balance
    SLOW(6),
    SLOWER(7),
    VERYSLOW(8);    // Slowest, highest quality
    
    companion object {
        fun fromValue(value: Int): EncoderPreset = 
            entries.getOrElse(value) { ULTRAFAST }
    }
}

/**
 * Streaming configuration.
 */
data class StreamConfig(
    val transport: TransportMode = TransportMode.UDP,  // Default to UDP for Bondix
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
    val useProxy: Boolean = true,
    // Encoder settings
    val encoderPreset: EncoderPreset = EncoderPreset.ULTRAFAST,
    val keyframeInterval: Int = 2,  // Keyframe every N seconds
    val bFrames: Int = 0            // B-frames (0 for low latency)
)

/**
 * SRT connection state.
 */
enum class SrtConnectionState(val value: Int) {
    DISCONNECTED(0),
    CONNECTING(1),
    CONNECTED(2),
    BROKEN(3);
    
    companion object {
        fun fromOrdinal(ordinal: Int): SrtConnectionState = 
            entries.getOrElse(ordinal) { DISCONNECTED }
    }
}

/**
 * Streaming statistics.
 */
data class StreamStats(
    val currentBitrate: Double = 0.0,
    val bytesSent: Long = 0,
    val packetsLost: Long = 0,
    val rtt: Double = 0.0,
    val streamTimeMs: Long = 0,
    val packetsRetransmitted: Long = 0,
    val packetsDropped: Long = 0,
    val bandwidth: Long = 0,
    val connectionState: SrtConnectionState = SrtConnectionState.DISCONNECTED
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
    
    /**
     * Get bandwidth in Mbps.
     */
    fun getBandwidthMbps(): Double = bandwidth / 1_000_000.0
}

