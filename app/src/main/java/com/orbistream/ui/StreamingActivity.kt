package com.orbistream.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.Surface
import android.view.View
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.orbistream.OrbiStreamApp
import com.orbistream.R
import com.orbistream.bondix.BondixStats
import com.orbistream.bondix.NetworkRegistry
import com.orbistream.databinding.ActivityStreamingBinding
import com.orbistream.streaming.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * StreamingActivity manages the live streaming session.
 * 
 * It handles:
 * - Camera preview and capture
 * - Audio capture
 * - Streaming service binding
 * - Real-time statistics display
 * - Network status monitoring
 */
class StreamingActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "StreamingActivity"
    }

    private lateinit var binding: ActivityStreamingBinding
    private lateinit var cameraManager: CameraManager
    private lateinit var audioCapture: AudioCapture
    
    private var streamingService: StreamingService? = null
    private var serviceBound = false
    private var bondixStatsJob: Job? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as StreamingService.LocalBinder
            streamingService = binder.getService()
            serviceBound = true
            Log.d(TAG, "Service connected")
            
            // Start observing service state
            observeServiceState()
            
                // Start camera and audio capture
            startCapture()
            
            // Start Bondix stats polling
            startBondixStatsPolling()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            streamingService = null
            serviceBound = false
            Log.d(TAG, "Service disconnected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Keep screen on while streaming
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // Enable edge-to-edge and immersive fullscreen
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        binding = ActivityStreamingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Hide system bars for immersive experience
        hideSystemBars()
        
        // Initialize camera and audio
        cameraManager = CameraManager(this)
        audioCapture = AudioCapture(this)
        
        setupUI()
        setupNetworkStatusListener()
        
        // Start streaming service
        startStreamingService()
    }
    
    private fun hideSystemBars() {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.apply {
            // Hide both system bars
            hide(WindowInsetsCompat.Type.systemBars())
            // Allow swipe to temporarily reveal
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
    
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        }
    }

    private fun setupUI() {
        binding.btnStopStream.setOnClickListener {
            stopStreaming()
        }

        // Display target
        val settings = OrbiStreamApp.instance.settingsRepository
        val srtUrl = "srt://${settings.srtHost}:${settings.srtPort}"
        binding.streamingToText.text = getString(R.string.streaming_to, srtUrl)
        
        // Initial state
        updateLiveIndicator(false)
    }

    private fun setupNetworkStatusListener() {
        NetworkRegistry.addStatusListener(object : NetworkRegistry.NetworkStatusListener {
            override fun onNetworkStatusChanged(wifiAvailable: Boolean, cellularAvailable: Boolean) {
                runOnUiThread {
                    updateNetworkPills(wifiAvailable, cellularAvailable)
                }
            }
        })
        
        // Initial update
        updateNetworkPills(
            NetworkRegistry.isWifiAvailable(),
            NetworkRegistry.isCellularAvailable()
        )
    }

    private fun updateNetworkPills(wifiAvailable: Boolean, cellularAvailable: Boolean) {
        updatePillIndicator(binding.wifiPillIndicator, wifiAvailable)
        updatePillIndicator(binding.cellularPillIndicator, cellularAvailable)
        
        // Update pill text with interface names
        binding.wifiPillText.text = if (wifiAvailable) "WiFi ●" else "WiFi"
        binding.cellularPillText.text = if (cellularAvailable) "LTE ●" else "LTE"
    }

    private fun updatePillIndicator(indicator: View, connected: Boolean) {
        val color = if (connected) {
            ContextCompat.getColor(this, R.color.status_connected)
        } else {
            ContextCompat.getColor(this, R.color.status_disconnected)
        }
        (indicator.background as? GradientDrawable)?.setColor(color)
    }

    private fun startStreamingService() {
        val settings = OrbiStreamApp.instance.settingsRepository
        val config = settings.buildStreamConfig()
        
        Log.i(TAG, "========================================")
        Log.i(TAG, "=== STARTING STREAMING SERVICE ===")
        Log.i(TAG, "SRT Target: srt://${config.srtHost}:${config.srtPort}")
        Log.i(TAG, "Stream ID: ${config.streamId ?: "(none)"}")
        Log.i(TAG, "Video: ${config.videoWidth}x${config.videoHeight} @ ${config.frameRate}fps")
        Log.i(TAG, "Video bitrate: ${config.videoBitrate / 1000} kbps")
        Log.i(TAG, "Audio: ${config.sampleRate}Hz @ ${config.audioBitrate / 1000} kbps")
        Log.i(TAG, "========================================")
        
        val intent = Intent(this, StreamingService::class.java).apply {
            action = StreamingService.ACTION_START
            putExtra(StreamingService.EXTRA_SRT_HOST, config.srtHost)
            putExtra(StreamingService.EXTRA_SRT_PORT, config.srtPort)
            putExtra(StreamingService.EXTRA_STREAM_ID, config.streamId)
            putExtra(StreamingService.EXTRA_PASSPHRASE, config.passphrase)
            putExtra(StreamingService.EXTRA_VIDEO_WIDTH, config.videoWidth)
            putExtra(StreamingService.EXTRA_VIDEO_HEIGHT, config.videoHeight)
            putExtra(StreamingService.EXTRA_VIDEO_BITRATE, config.videoBitrate)
            putExtra(StreamingService.EXTRA_FRAME_RATE, config.frameRate)
            putExtra(StreamingService.EXTRA_AUDIO_BITRATE, config.audioBitrate)
            putExtra(StreamingService.EXTRA_SAMPLE_RATE, config.sampleRate)
        }
        
        startForegroundService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun observeServiceState() {
        val service = streamingService ?: return
        
        // Observe stream state
        lifecycleScope.launch {
            service.streamState.collectLatest { state ->
                when (state) {
                    StreamState.STREAMING -> {
                        updateLiveIndicator(true)
                    }
                    StreamState.ERROR -> {
                        updateLiveIndicator(false)
                        Toast.makeText(this@StreamingActivity, 
                            R.string.error_stream_failed, Toast.LENGTH_LONG).show()
                    }
                    StreamState.STOPPED -> {
                        updateLiveIndicator(false)
                    }
                    else -> {}
                }
            }
        }
        
        // Observe stream stats
        lifecycleScope.launch {
            service.streamStats.collectLatest { stats ->
                stats?.let { updateStats(it) }
            }
        }
    }

    private fun startCapture() {
        val settings = OrbiStreamApp.instance.settingsRepository
        val (width, height) = settings.getResolutionSize()
        
        // Configure camera for landscape orientation
        cameraManager.setTargetResolution(android.util.Size(width, height))
        cameraManager.setTargetRotation(windowManager.defaultDisplay.rotation)
        cameraManager.setFrameCallback { data, w, h, timestamp ->
            streamingService?.pushVideoFrame(data, w, h, timestamp)
        }
        cameraManager.start(this, binding.cameraPreview)
        
        // Configure audio
        audioCapture.configure(settings.sampleRate, 2)
        audioCapture.setAudioCallback { data, sampleRate, channels, timestamp ->
            streamingService?.pushAudioSamples(data, sampleRate, channels, timestamp)
        }
        audioCapture.start()
        
        Log.i(TAG, "Capture started: ${width}x${height} @ rotation ${windowManager.defaultDisplay.rotation}")
    }

    private fun updateLiveIndicator(isLive: Boolean) {
        val color = if (isLive) {
            ContextCompat.getColor(this, R.color.status_streaming)
        } else {
            ContextCompat.getColor(this, R.color.status_disconnected)
        }
        (binding.liveIndicator.background as? GradientDrawable)?.setColor(color)
        
        binding.liveText.text = if (isLive) "LIVE" else "OFF"
        binding.liveText.setTextColor(color)
    }

    private fun updateStats(stats: StreamStats) {
        binding.streamTime.text = stats.getFormattedDuration()
        binding.bitrateDisplay.text = String.format("%.1f Mbps", stats.getBitrateMbps())
    }

    private fun startBondixStatsPolling() {
        bondixStatsJob?.cancel()
        bondixStatsJob = lifecycleScope.launch {
            while (isActive) {
                val stats = OrbiStreamApp.instance.bondixManager.getStats()
                stats?.let { updateBondixStats(it) }
                delay(1000) // Poll every second
            }
        }
    }

    private fun updateBondixStats(stats: BondixStats) {
        // Show Bondix stats card if connected
        binding.bondixStatsCard.visibility = if (stats.connected) View.VISIBLE else View.GONE
        
        if (stats.connected) {
            // Total bitrate
            binding.bondixTotalBitrate.text = "↑ ${stats.getFormattedTxBitrate()}"
            
            // Latency and loss
            binding.bondixLatency.text = String.format(
                "RTT: %.0fms • Loss: %.1f%%",
                stats.latencyMs,
                stats.packetLoss
            )
            
            // Update interface pills with per-interface stats
            stats.interfaces["WIFI"]?.let { wifi ->
                if (wifi.active) {
                    binding.wifiPillText.text = "WiFi ${wifi.getFormattedTxBitrate()}"
                }
            }
            
            stats.interfaces["CELLULAR"]?.let { cellular ->
                if (cellular.active) {
                    binding.cellularPillText.text = "LTE ${cellular.getFormattedTxBitrate()}"
                }
            }
        }
    }

    private fun stopStreaming() {
        // Stop Bondix stats polling
        bondixStatsJob?.cancel()
        
        // Stop capture
        cameraManager.stop()
        audioCapture.stop()
        
        // Stop service
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        
        val stopIntent = Intent(this, StreamingService::class.java).apply {
            action = StreamingService.ACTION_STOP
        }
        startService(stopIntent)
        
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        
        cameraManager.release()
        audioCapture.stop()
        
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    override fun onBackPressed() {
        // Confirm before stopping stream
        stopStreaming()
    }
}

