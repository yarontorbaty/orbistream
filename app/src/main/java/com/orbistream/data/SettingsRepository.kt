package com.orbistream.data

import android.content.Context
import android.content.SharedPreferences
import com.orbistream.streaming.StreamConfig
import com.orbistream.streaming.TransportMode

/**
 * SettingsRepository persists app settings using SharedPreferences.
 */
class SettingsRepository(context: Context) {
    
    companion object {
        private const val PREFS_NAME = "orbistream_settings"
        
        // Transport settings
        private const val KEY_TRANSPORT_MODE = "transport_mode"  // "udp" or "srt"
        
        // SRT/UDP target settings
        private const val KEY_SRT_HOST = "srt_host"
        private const val KEY_SRT_PORT = "srt_port"
        private const val KEY_STREAM_ID = "stream_id"
        private const val KEY_PASSPHRASE = "passphrase"
        
        // Bondix settings
        private const val KEY_TUNNEL_NAME = "tunnel_name"
        private const val KEY_TUNNEL_PASSWORD = "tunnel_password"
        private const val KEY_ENDPOINT_SERVER = "endpoint_server"
        
        // Video settings
        private const val KEY_RESOLUTION = "resolution"
        private const val KEY_VIDEO_BITRATE = "video_bitrate"
        private const val KEY_FRAME_RATE = "frame_rate"
        
        // Audio settings
        private const val KEY_AUDIO_BITRATE = "audio_bitrate"
        private const val KEY_SAMPLE_RATE = "sample_rate"
        
        // Defaults
        private const val DEFAULT_SRT_PORT = 5000
        private const val DEFAULT_VIDEO_BITRATE = 4000 // kbps
        private const val DEFAULT_FRAME_RATE = 30
        private const val DEFAULT_AUDIO_BITRATE = 128 // kbps
        private const val DEFAULT_SAMPLE_RATE = 48000
    }

    private val prefs: SharedPreferences = 
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Transport Mode
    var transportMode: TransportMode
        get() {
            val mode = prefs.getString(KEY_TRANSPORT_MODE, "udp") ?: "udp"
            return if (mode == "srt") TransportMode.SRT else TransportMode.UDP
        }
        set(value) {
            val mode = if (value == TransportMode.SRT) "srt" else "udp"
            prefs.edit().putString(KEY_TRANSPORT_MODE, mode).apply()
        }

    // Target Settings (used for both UDP and SRT)
    var srtHost: String
        get() = prefs.getString(KEY_SRT_HOST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SRT_HOST, value).apply()

    var srtPort: Int
        get() = prefs.getInt(KEY_SRT_PORT, DEFAULT_SRT_PORT)
        set(value) = prefs.edit().putInt(KEY_SRT_PORT, value).apply()

    var streamId: String
        get() = prefs.getString(KEY_STREAM_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_STREAM_ID, value).apply()

    var passphrase: String
        get() = prefs.getString(KEY_PASSPHRASE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PASSPHRASE, value).apply()

    // Bondix Settings
    var tunnelName: String
        get() = prefs.getString(KEY_TUNNEL_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TUNNEL_NAME, value).apply()

    var tunnelPassword: String
        get() = prefs.getString(KEY_TUNNEL_PASSWORD, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TUNNEL_PASSWORD, value).apply()

    var endpointServer: String
        get() = prefs.getString(KEY_ENDPOINT_SERVER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ENDPOINT_SERVER, value).apply()

    // Video Settings
    var resolution: String
        get() = prefs.getString(KEY_RESOLUTION, "1080p") ?: "1080p"
        set(value) = prefs.edit().putString(KEY_RESOLUTION, value).apply()

    var videoBitrateKbps: Int
        get() = prefs.getInt(KEY_VIDEO_BITRATE, DEFAULT_VIDEO_BITRATE)
        set(value) = prefs.edit().putInt(KEY_VIDEO_BITRATE, value).apply()

    var frameRate: Int
        get() = prefs.getInt(KEY_FRAME_RATE, DEFAULT_FRAME_RATE)
        set(value) = prefs.edit().putInt(KEY_FRAME_RATE, value).apply()

    // Audio Settings
    var audioBitrateKbps: Int
        get() = prefs.getInt(KEY_AUDIO_BITRATE, DEFAULT_AUDIO_BITRATE)
        set(value) = prefs.edit().putInt(KEY_AUDIO_BITRATE, value).apply()

    var sampleRate: Int
        get() = prefs.getInt(KEY_SAMPLE_RATE, DEFAULT_SAMPLE_RATE)
        set(value) = prefs.edit().putInt(KEY_SAMPLE_RATE, value).apply()

    /**
     * Check if SRT settings are configured.
     */
    fun hasSrtSettings(): Boolean = srtHost.isNotBlank()

    /**
     * Check if Bondix settings are configured.
     */
    fun hasBondixSettings(): Boolean = 
        tunnelName.isNotBlank() && tunnelPassword.isNotBlank() && endpointServer.isNotBlank()

    /**
     * Get resolution as width/height pair.
     */
    fun getResolutionSize(): Pair<Int, Int> {
        return when (resolution.lowercase()) {
            "480p" -> Pair(854, 480)
            "720p" -> Pair(1280, 720)
            "1080p" -> Pair(1920, 1080)
            "1440p" -> Pair(2560, 1440)
            "4k", "2160p" -> Pair(3840, 2160)
            else -> Pair(1920, 1080)
        }
    }

    /**
     * Build StreamConfig from current settings.
     */
    fun buildStreamConfig(): StreamConfig {
        val (width, height) = getResolutionSize()
        return StreamConfig(
            transport = transportMode,
            srtHost = srtHost,
            srtPort = srtPort,
            streamId = streamId.takeIf { it.isNotBlank() },
            passphrase = passphrase.takeIf { it.isNotBlank() },
            videoWidth = width,
            videoHeight = height,
            videoBitrate = videoBitrateKbps * 1000,
            frameRate = frameRate,
            audioBitrate = audioBitrateKbps * 1000,
            sampleRate = sampleRate
        )
    }

    /**
     * Clear all settings.
     */
    fun clear() {
        prefs.edit().clear().apply()
    }
}

