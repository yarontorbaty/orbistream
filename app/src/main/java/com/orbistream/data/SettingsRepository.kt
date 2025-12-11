package com.orbistream.data

import android.content.Context
import android.content.SharedPreferences
import com.orbistream.streaming.EncoderPreset
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
        private const val KEY_BONDIX_ENABLED = "bondix_enabled"
        private const val KEY_BONDIX_FOR_SRT = "bondix_for_srt"  // Route SRT through Bondix tunnel
        private const val KEY_TUNNEL_NAME = "tunnel_name"
        private const val KEY_TUNNEL_PASSWORD = "tunnel_password"
        private const val KEY_ENDPOINT_SERVER = "endpoint_server"
        
        // Video settings
        private const val KEY_RESOLUTION = "resolution"
        private const val KEY_VIDEO_BITRATE = "video_bitrate"
        private const val KEY_FRAME_RATE = "frame_rate"
        
        // Encoder settings
        private const val KEY_ENCODER_PRESET = "encoder_preset"
        private const val KEY_KEYFRAME_INTERVAL = "keyframe_interval"
        private const val KEY_B_FRAMES = "b_frames"
        private const val KEY_USE_HARDWARE_ENCODER = "use_hardware_encoder"
        
        // Audio settings
        private const val KEY_AUDIO_BITRATE = "audio_bitrate"
        private const val KEY_SAMPLE_RATE = "sample_rate"
        
        // Defaults
        private const val DEFAULT_SRT_HOST = "gcdemo.mcrbox.com"
        private const val DEFAULT_SRT_PORT = 5000
        private const val DEFAULT_RESOLUTION = "720p"
        private const val DEFAULT_VIDEO_BITRATE = 2000 // kbps
        private const val DEFAULT_FRAME_RATE = 30
        private const val DEFAULT_ENCODER_PRESET = 0 // ULTRAFAST
        private const val DEFAULT_KEYFRAME_INTERVAL = 2 // seconds
        private const val DEFAULT_B_FRAMES = 0
        private const val DEFAULT_AUDIO_BITRATE = 128 // kbps
        private const val DEFAULT_SAMPLE_RATE = 48000
        
        // Bondix defaults
        private const val DEFAULT_BONDIX_ENABLED = true
        private const val DEFAULT_BONDIX_FOR_SRT = true  // Route SRT through Bondix by default
        private const val DEFAULT_TUNNEL_NAME = "gcdemo"
        private const val DEFAULT_TUNNEL_PASSWORD = "_'rY8.*Z1!Jh"
        private const val DEFAULT_ENDPOINT_SERVER = "gcdemo.mcrbox.com"
        
        // Reconnect settings keys
        private const val KEY_AUTO_RECONNECT = "auto_reconnect"
        private const val KEY_INFINITE_RETRIES = "infinite_retries"
        private const val KEY_MAX_RECONNECT_ATTEMPTS = "max_reconnect_attempts"
        private const val KEY_USE_EXPONENTIAL_BACKOFF = "use_exponential_backoff"
        private const val KEY_RECONNECT_DELAY_SEC = "reconnect_delay_sec"
        
        // Reconnect defaults
        private const val DEFAULT_AUTO_RECONNECT = true
        private const val DEFAULT_INFINITE_RETRIES = true
        private const val DEFAULT_MAX_RECONNECT_ATTEMPTS = 10
        private const val DEFAULT_USE_EXPONENTIAL_BACKOFF = false
        private const val DEFAULT_RECONNECT_DELAY_SEC = 3
    }

    private val prefs: SharedPreferences = 
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Transport Mode (default: SRT)
    var transportMode: TransportMode
        get() {
            val mode = prefs.getString(KEY_TRANSPORT_MODE, "srt") ?: "srt"
            return if (mode == "srt") TransportMode.SRT else TransportMode.UDP
        }
        set(value) {
            val mode = if (value == TransportMode.SRT) "srt" else "udp"
            prefs.edit().putString(KEY_TRANSPORT_MODE, mode).apply()
        }

    // Target Settings (used for both UDP and SRT)
    var srtHost: String
        get() = prefs.getString(KEY_SRT_HOST, DEFAULT_SRT_HOST) ?: DEFAULT_SRT_HOST
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
    var bondixEnabled: Boolean
        get() = prefs.getBoolean(KEY_BONDIX_ENABLED, DEFAULT_BONDIX_ENABLED)
        set(value) = prefs.edit().putBoolean(KEY_BONDIX_ENABLED, value).apply()

    var bondixForSrt: Boolean
        get() = prefs.getBoolean(KEY_BONDIX_FOR_SRT, DEFAULT_BONDIX_FOR_SRT)
        set(value) = prefs.edit().putBoolean(KEY_BONDIX_FOR_SRT, value).apply()

    var tunnelName: String
        get() = prefs.getString(KEY_TUNNEL_NAME, DEFAULT_TUNNEL_NAME) ?: DEFAULT_TUNNEL_NAME
        set(value) = prefs.edit().putString(KEY_TUNNEL_NAME, value).apply()

    var tunnelPassword: String
        get() = prefs.getString(KEY_TUNNEL_PASSWORD, DEFAULT_TUNNEL_PASSWORD) ?: DEFAULT_TUNNEL_PASSWORD
        set(value) = prefs.edit().putString(KEY_TUNNEL_PASSWORD, value).apply()

    var endpointServer: String
        get() = prefs.getString(KEY_ENDPOINT_SERVER, DEFAULT_ENDPOINT_SERVER) ?: DEFAULT_ENDPOINT_SERVER
        set(value) = prefs.edit().putString(KEY_ENDPOINT_SERVER, value).apply()

    // Video Settings
    var resolution: String
        get() = prefs.getString(KEY_RESOLUTION, DEFAULT_RESOLUTION) ?: DEFAULT_RESOLUTION
        set(value) = prefs.edit().putString(KEY_RESOLUTION, value).apply()

    var videoBitrateKbps: Int
        get() = prefs.getInt(KEY_VIDEO_BITRATE, DEFAULT_VIDEO_BITRATE)
        set(value) = prefs.edit().putInt(KEY_VIDEO_BITRATE, value).apply()

    var frameRate: Int
        get() = prefs.getInt(KEY_FRAME_RATE, DEFAULT_FRAME_RATE)
        set(value) = prefs.edit().putInt(KEY_FRAME_RATE, value).apply()

    // Encoder Settings
    var encoderPreset: EncoderPreset
        get() = EncoderPreset.fromValue(prefs.getInt(KEY_ENCODER_PRESET, DEFAULT_ENCODER_PRESET))
        set(value) = prefs.edit().putInt(KEY_ENCODER_PRESET, value.value).apply()

    var keyframeInterval: Int
        get() = prefs.getInt(KEY_KEYFRAME_INTERVAL, DEFAULT_KEYFRAME_INTERVAL)
        set(value) = prefs.edit().putInt(KEY_KEYFRAME_INTERVAL, value).apply()

    var bFrames: Int
        get() = prefs.getInt(KEY_B_FRAMES, DEFAULT_B_FRAMES)
        set(value) = prefs.edit().putInt(KEY_B_FRAMES, value).apply()

    var useHardwareEncoder: Boolean
        get() = prefs.getBoolean(KEY_USE_HARDWARE_ENCODER, true)  // Default to true
        set(value) = prefs.edit().putBoolean(KEY_USE_HARDWARE_ENCODER, value).apply()

    // Reconnect Settings
    var autoReconnect: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RECONNECT, DEFAULT_AUTO_RECONNECT)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_RECONNECT, value).apply()
    
    var infiniteRetries: Boolean
        get() = prefs.getBoolean(KEY_INFINITE_RETRIES, DEFAULT_INFINITE_RETRIES)
        set(value) = prefs.edit().putBoolean(KEY_INFINITE_RETRIES, value).apply()
    
    var maxReconnectAttempts: Int
        get() = prefs.getInt(KEY_MAX_RECONNECT_ATTEMPTS, DEFAULT_MAX_RECONNECT_ATTEMPTS)
        set(value) = prefs.edit().putInt(KEY_MAX_RECONNECT_ATTEMPTS, value).apply()
    
    var useExponentialBackoff: Boolean
        get() = prefs.getBoolean(KEY_USE_EXPONENTIAL_BACKOFF, DEFAULT_USE_EXPONENTIAL_BACKOFF)
        set(value) = prefs.edit().putBoolean(KEY_USE_EXPONENTIAL_BACKOFF, value).apply()
    
    var reconnectDelaySec: Int
        get() = prefs.getInt(KEY_RECONNECT_DELAY_SEC, DEFAULT_RECONNECT_DELAY_SEC)
        set(value) = prefs.edit().putInt(KEY_RECONNECT_DELAY_SEC, value).apply()

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
     * Check if Bondix settings are configured and enabled.
     */
    fun hasBondixSettings(): Boolean = 
        bondixEnabled && tunnelName.isNotBlank() && tunnelPassword.isNotBlank() && endpointServer.isNotBlank()
    
    /**
     * Check if Bondix is enabled (regardless of whether configured).
     */
    fun isBondixEnabled(): Boolean = bondixEnabled

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
            sampleRate = sampleRate,
            encoderPreset = encoderPreset,
            keyframeInterval = keyframeInterval,
            bFrames = bFrames,
            useHardwareEncoder = useHardwareEncoder
        )
    }

    /**
     * Clear all settings.
     */
    fun clear() {
        prefs.edit().clear().apply()
    }
    
    /**
     * Restore all settings to defaults.
     */
    fun restoreDefaults() {
        prefs.edit().clear().apply()
        // After clearing, all getters will return their default values
    }
}

