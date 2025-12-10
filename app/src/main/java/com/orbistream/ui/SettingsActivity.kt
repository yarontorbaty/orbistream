package com.orbistream.ui

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.orbistream.OrbiStreamApp
import com.orbistream.R
import com.orbistream.databinding.ActivitySettingsBinding
import com.orbistream.data.SettingsRepository

/**
 * SettingsActivity allows users to configure:
 * - SRT streaming destination
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
        
        setupDropdowns()
        loadSettings()
        setupClickListeners()
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
        // SRT settings
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
        // Validate SRT host
        val srtHost = binding.inputSrtHost.text.toString().trim()
        if (srtHost.isBlank()) {
            binding.inputSrtHost.error = "SRT host is required"
            return
        }

        // Validate SRT port
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

        // Save SRT settings
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
            OrbiStreamApp.instance.configureBondixIfReady()
        }

        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        finish()
    }
}

