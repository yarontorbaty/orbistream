package com.orbistream.streaming

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.orbistream.OrbiStreamApp
import com.orbistream.R
import com.orbistream.bondix.BondixManager
import com.orbistream.bondix.Socks5UdpRelay
import com.orbistream.ui.StreamingActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * StreamingService runs as a foreground service to manage the streaming session.
 * 
 * This ensures the stream continues even when the app is in the background.
 */
class StreamingService : Service() {
    
    companion object {
        private const val TAG = "StreamingService"
        private const val CHANNEL_ID = "orbistream_streaming"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.orbistream.action.START"
        const val ACTION_STOP = "com.orbistream.action.STOP"
        
        const val EXTRA_SRT_HOST = "srt_host"
        const val EXTRA_SRT_PORT = "srt_port"
        const val EXTRA_STREAM_ID = "stream_id"
        const val EXTRA_PASSPHRASE = "passphrase"
        const val EXTRA_VIDEO_WIDTH = "video_width"
        const val EXTRA_VIDEO_HEIGHT = "video_height"
        const val EXTRA_VIDEO_BITRATE = "video_bitrate"
        const val EXTRA_FRAME_RATE = "frame_rate"
        const val EXTRA_AUDIO_BITRATE = "audio_bitrate"
        const val EXTRA_SAMPLE_RATE = "sample_rate"
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private val _streamState = MutableStateFlow(StreamState.IDLE)
    val streamState: StateFlow<StreamState> = _streamState
    
    private val _streamStats = MutableStateFlow<StreamStats?>(null)
    val streamStats: StateFlow<StreamStats?> = _streamStats
    
    private var statsJob: Job? = null
    private var currentConfig: StreamConfig? = null
    private var udpRelay: Socks5UdpRelay? = null

    inner class LocalBinder : Binder() {
        fun getService(): StreamingService = this@StreamingService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        createNotificationChannel()
        NativeStreamer.initialize()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val config = extractConfig(intent)
                startStreaming(config)
            }
            ACTION_STOP -> {
                stopStreaming()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        stopStreaming()
        serviceScope.cancel()
        NativeStreamer.destroy()
    }

    private fun extractConfig(intent: Intent): StreamConfig {
        return StreamConfig(
            srtHost = intent.getStringExtra(EXTRA_SRT_HOST) ?: "localhost",
            srtPort = intent.getIntExtra(EXTRA_SRT_PORT, 9000),
            streamId = intent.getStringExtra(EXTRA_STREAM_ID),
            passphrase = intent.getStringExtra(EXTRA_PASSPHRASE),
            videoWidth = intent.getIntExtra(EXTRA_VIDEO_WIDTH, 1920),
            videoHeight = intent.getIntExtra(EXTRA_VIDEO_HEIGHT, 1080),
            videoBitrate = intent.getIntExtra(EXTRA_VIDEO_BITRATE, 4_000_000),
            frameRate = intent.getIntExtra(EXTRA_FRAME_RATE, 30),
            audioBitrate = intent.getIntExtra(EXTRA_AUDIO_BITRATE, 128_000),
            sampleRate = intent.getIntExtra(EXTRA_SAMPLE_RATE, 48000)
        )
    }

    private fun startStreaming(config: StreamConfig) {
        if (_streamState.value == StreamState.STREAMING) {
            Log.w(TAG, "Already streaming")
            return
        }

        // Check if Bondix is available and configured
        val app = application as? OrbiStreamApp
        val bondixAvailable = app?.isBondixReady() == true && 
                              app.settingsRepository.hasBondixSettings()
        
        // Use transport mode from config (which comes from settings)
        val isUdpMode = config.transport == TransportMode.UDP
        val useBondixRelay = isUdpMode && bondixAvailable
        
        val protocol = when {
            isUdpMode && bondixAvailable -> "UDP (via Bondix)"
            isUdpMode -> "UDP (direct - Bondix not available)"
            else -> "SRT"
        }
        
        Log.i(TAG, "========================================")
        Log.i(TAG, "=== STREAMING SERVICE: START ===")
        Log.i(TAG, "Transport: $protocol")
        Log.i(TAG, "Target: ${config.srtHost}:${config.srtPort}")
        Log.i(TAG, "Stream ID: ${config.streamId ?: "(none)"}")
        Log.i(TAG, "Bondix: ${if (bondixAvailable) "available" else "not available"}")
        Log.i(TAG, "========================================")
        
        if (useBondixRelay) {
            Log.i(TAG, "Using UDP transport via Bondix bonded tunnel")
            
            // Start UDP relay asynchronously, then continue with streaming
            serviceScope.launch {
                val actualConfig = setupBondixRelay(config)
                continueStartStreaming(actualConfig)
            }
        } else {
            if (isUdpMode) {
                Log.w(TAG, "UDP mode selected but Bondix not available - streaming UDP directly")
            } else {
                Log.i(TAG, "SRT mode selected - streaming with SRT reliability")
            }
            continueStartStreaming(config)
        }
    }
    
    private suspend fun setupBondixRelay(config: StreamConfig): StreamConfig {
        return withContext(Dispatchers.IO) {
            try {
                // Wait for Bondix tunnel to establish
                // The SOCKS5 proxy only starts after the tunnel connects to the server
                // This may take several seconds depending on network conditions
                Log.i(TAG, "Waiting for Bondix tunnel to establish (3 seconds)...")
                delay(3000)
                
                // Create UDP relay to forward SRT through Bondix SOCKS5
                val relay = Socks5UdpRelay(
                    socksHost = BondixManager.DEFAULT_PROXY_HOST,
                    socksPort = BondixManager.DEFAULT_PROXY_PORT,
                    targetHost = config.srtHost,
                    targetPort = config.srtPort,
                    localPort = 0 // Auto-assign
                )
                
                if (relay.start()) {
                    udpRelay = relay
                    val localPort = relay.getLocalPort()
                    
                    Log.i(TAG, "========================================")
                    Log.i(TAG, "=== BONDIX UDP RELAY ACTIVE ===")
                    Log.i(TAG, "Local: 127.0.0.1:$localPort")
                    Log.i(TAG, "Target: ${config.srtHost}:${config.srtPort}")
                    Log.i(TAG, "Via: Bondix SOCKS5 tunnel")
                    Log.i(TAG, "========================================")
                    
                    // Return modified config pointing to local relay
                    config.copy(
                        srtHost = "127.0.0.1",
                        srtPort = localPort
                    )
                } else {
                    Log.e(TAG, "Failed to start Bondix UDP relay - streaming directly")
                    config
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up Bondix relay: ${e.message}")
                config
            }
        }
    }
    
    private fun continueStartStreaming(config: StreamConfig) {
        currentConfig = config
        _streamState.value = StreamState.STARTING

        // Start foreground notification
        startForeground(NOTIFICATION_ID, createNotification())

        // Set up streaming callback
        NativeStreamer.setCallback(object : NativeStreamer.StreamCallback {
            override fun onStateChanged(running: Boolean, message: String) {
                serviceScope.launch {
                    _streamState.value = if (running) StreamState.STREAMING else StreamState.STOPPED
                    Log.i(TAG, "=== Stream State Changed ===")
                    Log.i(TAG, "Running: $running")
                    Log.i(TAG, "Message: $message")
                }
            }

            override fun onStatsUpdated(bitrate: Double, bytesSent: Long, packetsLost: Long, rtt: Double, streamTimeMs: Long) {
                serviceScope.launch {
                    _streamStats.value = StreamStats(bitrate, bytesSent, packetsLost, rtt, streamTimeMs)
                    // Log stats periodically (every ~5 seconds based on polling)
                    if (bytesSent > 0) {
                        Log.d(TAG, "Stats: ${String.format("%.1f", bitrate/1000)}kbps, ${bytesSent/1024}KB sent, RTT=${String.format("%.1f", rtt)}ms")
                    }
                }
            }

            override fun onError(error: String) {
                Log.e(TAG, "!!! STREAMING ERROR !!!")
                Log.e(TAG, "Error: $error")
                serviceScope.launch {
                    _streamState.value = StreamState.ERROR
                }
            }
        })

        // Create and start pipeline
        Log.i(TAG, "Creating GStreamer pipeline...")
        if (NativeStreamer.createPipeline(config)) {
            Log.i(TAG, "Pipeline created, starting stream...")
            if (NativeStreamer.start()) {
                Log.i(TAG, "=== STREAM STARTED SUCCESSFULLY ===")
                _streamState.value = StreamState.STREAMING
                startStatsPolling()
            } else {
                Log.e(TAG, "!!! FAILED TO START STREAM !!!")
                _streamState.value = StreamState.ERROR
                stopSelf()
            }
        } else {
            Log.e(TAG, "!!! FAILED TO CREATE PIPELINE !!!")
            _streamState.value = StreamState.ERROR
            stopSelf()
        }
    }

    private fun stopStreaming() {
        statsJob?.cancel()
        NativeStreamer.stop()
        
        // Stop UDP relay if running
        udpRelay?.let {
            Log.i(TAG, "Stopping Bondix UDP relay")
            it.stop()
            udpRelay = null
        }
        
        _streamState.value = StreamState.STOPPED
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startStatsPolling() {
        statsJob?.cancel()
        statsJob = serviceScope.launch {
            while (isActive && NativeStreamer.isStreaming()) {
                NativeStreamer.getStats()?.let { stats ->
                    _streamStats.value = stats
                    updateNotification(stats)
                }
                delay(1000)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
            
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(stats: StreamStats? = null): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, StreamingActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, StreamingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = if (stats != null) {
            "${stats.getFormattedDuration()} • ${String.format("%.1f", stats.getBitrateMbps())} Mbps"
        } else {
            getString(R.string.notification_streaming_text)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_streaming_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_stream)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.btn_stop_stream), stopIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(stats: StreamStats) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification(stats))
    }

    /**
     * Push a video frame to the stream.
     */
    fun pushVideoFrame(data: ByteArray, width: Int, height: Int, timestampNs: Long) {
        if (_streamState.value == StreamState.STREAMING) {
            NativeStreamer.pushVideoFrame(data, width, height, timestampNs)
        }
    }

    /**
     * Push audio samples to the stream.
     */
    fun pushAudioSamples(data: ByteArray, sampleRate: Int, channels: Int, timestampNs: Long) {
        if (_streamState.value == StreamState.STREAMING) {
            NativeStreamer.pushAudioSamples(data, sampleRate, channels, timestampNs)
        }
    }

    /**
     * Check if currently streaming.
     */
    fun isStreaming(): Boolean = _streamState.value == StreamState.STREAMING

    /**
     * Get current configuration.
     */
    fun getConfig(): StreamConfig? = currentConfig
}

/**
 * Stream state enumeration.
 */
enum class StreamState {
    IDLE,
    STARTING,
    STREAMING,
    STOPPING,
    STOPPED,
    ERROR
}

