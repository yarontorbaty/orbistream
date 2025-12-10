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
import com.orbistream.R
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

        currentConfig = config
        _streamState.value = StreamState.STARTING

        // Start foreground notification
        startForeground(NOTIFICATION_ID, createNotification())

        // Set up streaming callback
        NativeStreamer.setCallback(object : NativeStreamer.StreamCallback {
            override fun onStateChanged(running: Boolean, message: String) {
                serviceScope.launch {
                    _streamState.value = if (running) StreamState.STREAMING else StreamState.STOPPED
                    Log.d(TAG, "State changed: running=$running, message=$message")
                }
            }

            override fun onStatsUpdated(bitrate: Double, bytesSent: Long, packetsLost: Long, rtt: Double, streamTimeMs: Long) {
                serviceScope.launch {
                    _streamStats.value = StreamStats(bitrate, bytesSent, packetsLost, rtt, streamTimeMs)
                }
            }

            override fun onError(error: String) {
                Log.e(TAG, "Streaming error: $error")
                serviceScope.launch {
                    _streamState.value = StreamState.ERROR
                }
            }
        })

        // Create and start pipeline
        if (NativeStreamer.createPipeline(config)) {
            if (NativeStreamer.start()) {
                _streamState.value = StreamState.STREAMING
                startStatsPolling()
            } else {
                _streamState.value = StreamState.ERROR
                stopSelf()
            }
        } else {
            _streamState.value = StreamState.ERROR
            stopSelf()
        }
    }

    private fun stopStreaming() {
        statsJob?.cancel()
        NativeStreamer.stop()
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

