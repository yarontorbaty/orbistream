package com.orbistream.ui

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.orbistream.OrbiStreamApp
import com.orbistream.R
import com.orbistream.databinding.ActivitySettingsBinding
import com.orbistream.data.SettingsRepository
import com.orbistream.streaming.TransportMode

/**
 * SettingsActivity allows users to configure:
 * - Transport mode (UDP or SRT)
 * - Streaming destination
 * - Bondix tunnel credentials
 * - Video and audio encoding settings
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: SettingsRepository

    private val resolutionOptions = listOf("480p", "720p", "1080p", "1440p", "4K")
    private val frameRateOptions = listOf("24", "25", "30", "50", "60")
    private val audioBitrateOptions = listOf("64", "96", "128", "192", "256", "320")
    private val sampleRateOptions = listOf("44100", "48000")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        settings = OrbiStreamApp.instance.settingsRepository
        
        setupTransportToggle()
        setupDropdowns()
        loadSettings()
        setupClickListeners()
    }
    
    private fun setupTransportToggle() {
        binding.toggleTransport.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                updateTransportDescription(checkedId == R.id.btnUdp)
            }
        }
    }
    
    private fun updateTransportDescription(isUdp: Boolean) {
        binding.transportDescription.text = if (isUdp) {
            "UDP: Use with Bondix (Bondix handles reliability)"
        } else {
            "SRT: Direct streaming with built-in reliability"
        }
        
        binding.transportHint.text = if (isUdp) {
            "Receiver: UDP MPEG-TS listener (e.g., VLC: udp://@:5000)\n" +
            "Best with Bondix for network bonding"
        } else {
            "Receiver: SRT listener (e.g., ffplay srt://0.0.0.0:5000?mode=listener)\n" +
            "Works without Bondix"
        }
    }

    private fun setupDropdowns() {
        // Resolution dropdown
        val resolutionAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, resolutionOptions)
        binding.inputResolution.setAdapter(resolutionAdapter)

        // Frame rate dropdown
        val frameRateAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, frameRateOptions)
        binding.inputFramerate.setAdapter(frameRateAdapter)

        // Audio bitrate dropdown
        val audioBitrateAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, audioBitrateOptions)
        binding.inputAudioBitrate.setAdapter(audioBitrateAdapter)

        // Sample rate dropdown
        val sampleRateAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, sampleRateOptions)
        binding.inputSampleRate.setAdapter(sampleRateAdapter)
    }

    private fun loadSettings() {
        // Transport mode
        val isUdp = settings.transportMode == TransportMode.UDP
        binding.toggleTransport.check(if (isUdp) R.id.btnUdp else R.id.btnSrt)
        updateTransportDescription(isUdp)
        
        // Destination settings
        binding.inputSrtHost.setText(settings.srtHost)
        binding.inputSrtPort.setText(settings.srtPort.toString())
        binding.inputStreamId.setText(settings.streamId)
        binding.inputPassphrase.setText(settings.passphrase)

        // Bondix settings
        binding.inputTunnelName.setText(settings.tunnelName)
        binding.inputTunnelPassword.setText(settings.tunnelPassword)
        binding.inputEndpointServer.setText(settings.endpointServer)

        // Video settings
        binding.inputResolution.setText(settings.resolution, false)
        binding.inputBitrate.setText(settings.videoBitrateKbps.toString())
        binding.inputFramerate.setText(settings.frameRate.toString(), false)

        // Audio settings
        binding.inputAudioBitrate.setText(settings.audioBitrateKbps.toString(), false)
        binding.inputSampleRate.setText(settings.sampleRate.toString(), false)
    }

    private fun setupClickListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnSave.setOnClickListener {
            saveSettings()
        }
    }

    private fun saveSettings() {
        // Validate host
        val srtHost = binding.inputSrtHost.text.toString().trim()
        if (srtHost.isBlank()) {
            binding.inputSrtHost.error = "Host is required"
            return
        }

        // Validate port
        val srtPort = binding.inputSrtPort.text.toString().toIntOrNull()
        if (srtPort == null || srtPort !in 1..65535) {
            binding.inputSrtPort.error = "Invalid port (1-65535)"
            return
        }

        // Validate video bitrate
        val videoBitrate = binding.inputBitrate.text.toString().toIntOrNull()
        if (videoBitrate == null || videoBitrate !in 500..50000) {
            binding.inputBitrate.error = "Invalid bitrate (500-50000 kbps)"
            return
        }

        // Save transport mode
        val isUdp = binding.toggleTransport.checkedButtonId == R.id.btnUdp
        settings.transportMode = if (isUdp) TransportMode.UDP else TransportMode.SRT
        
        // Save destination settings
        settings.srtHost = srtHost
        settings.srtPort = srtPort
        settings.streamId = binding.inputStreamId.text.toString().trim()
        settings.passphrase = binding.inputPassphrase.text.toString()

        // Save Bondix settings
        settings.tunnelName = binding.inputTunnelName.text.toString().trim()
        settings.tunnelPassword = binding.inputTunnelPassword.text.toString()
        settings.endpointServer = binding.inputEndpointServer.text.toString().trim()

        // Save video settings
        settings.resolution = binding.inputResolution.text.toString()
        settings.videoBitrateKbps = videoBitrate
        settings.frameRate = binding.inputFramerate.text.toString().toIntOrNull() ?: 30

        // Save audio settings
        settings.audioBitrateKbps = binding.inputAudioBitrate.text.toString().toIntOrNull() ?: 128
        settings.sampleRate = binding.inputSampleRate.text.toString().toIntOrNull() ?: 48000

        // Update Bondix configuration if settings changed
        if (settings.hasBondixSettings()) {
            OrbiStreamApp.instance.configureBondixIfReady(force = true)
        }

        val modeStr = if (isUdp) "UDP" else "SRT"
        Toast.makeText(this, "Settings saved ($modeStr mode)", Toast.LENGTH_SHORT).show()
        finish()
    }
}

