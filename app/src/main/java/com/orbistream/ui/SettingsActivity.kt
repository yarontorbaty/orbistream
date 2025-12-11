package com.orbistream.ui

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.orbistream.OrbiStreamApp
import com.orbistream.R
import com.orbistream.databinding.ActivitySettingsBinding
import com.orbistream.data.SettingsRepository
import com.orbistream.streaming.EncoderPreset
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
    private val encoderPresetOptions = listOf("Ultrafast", "Superfast", "Veryfast", "Faster", "Fast", "Medium", "Slow", "Slower", "Veryslow")
    private val keyframeIntervalOptions = listOf("1", "2", "3", "4", "5", "10")
    private val bFrameOptions = listOf("0", "1", "2", "3", "4")
    private val audioBitrateOptions = listOf("64", "96", "128", "192", "256", "320")
    private val sampleRateOptions = listOf("44100", "48000")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        settings = OrbiStreamApp.instance.settingsRepository
        
        setupTransportToggle()
        setupBondixToggle()
        setupDropdowns()
        loadSettings()
        setupClickListeners()
    }
    
    private fun setupBondixToggle() {
        binding.switchBondixEnabled.setOnCheckedChangeListener { _, isChecked ->
            updateBondixSettingsVisibility(isChecked)
        }
    }
    
    private fun updateBondixSettingsVisibility(enabled: Boolean) {
        binding.bondixSettingsContainer.visibility = if (enabled) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
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

        // Encoder preset dropdown
        val presetAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, encoderPresetOptions)
        binding.inputEncoderPreset.setAdapter(presetAdapter)

        // Keyframe interval dropdown
        val keyframeAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, keyframeIntervalOptions)
        binding.inputKeyframeInterval.setAdapter(keyframeAdapter)

        // B-frames dropdown
        val bFramesAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, bFrameOptions)
        binding.inputBFrames.setAdapter(bFramesAdapter)

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
        binding.switchBondixEnabled.isChecked = settings.bondixEnabled
        binding.switchBondixForSrt.isChecked = settings.bondixForSrt
        updateBondixSettingsVisibility(settings.bondixEnabled)
        binding.inputTunnelName.setText(settings.tunnelName)
        binding.inputTunnelPassword.setText(settings.tunnelPassword)
        binding.inputEndpointServer.setText(settings.endpointServer)

        // Video settings
        binding.inputResolution.setText(settings.resolution, false)
        binding.inputBitrate.setText(settings.videoBitrateKbps.toString())
        binding.inputFramerate.setText(settings.frameRate.toString(), false)

        // Encoder settings
        binding.switchHardwareEncoder.isChecked = settings.useHardwareEncoder
        binding.inputEncoderPreset.setText(presetToDisplayName(settings.encoderPreset), false)
        binding.inputKeyframeInterval.setText(settings.keyframeInterval.toString(), false)
        binding.inputBFrames.setText(settings.bFrames.toString(), false)

        // Audio settings
        binding.inputAudioBitrate.setText(settings.audioBitrateKbps.toString(), false)
        binding.inputSampleRate.setText(settings.sampleRate.toString(), false)
    }
    
    private fun presetToDisplayName(preset: EncoderPreset): String {
        return when (preset) {
            EncoderPreset.ULTRAFAST -> "Ultrafast"
            EncoderPreset.SUPERFAST -> "Superfast"
            EncoderPreset.VERYFAST -> "Veryfast"
            EncoderPreset.FASTER -> "Faster"
            EncoderPreset.FAST -> "Fast"
            EncoderPreset.MEDIUM -> "Medium"
            EncoderPreset.SLOW -> "Slow"
            EncoderPreset.SLOWER -> "Slower"
            EncoderPreset.VERYSLOW -> "Veryslow"
        }
    }
    
    private fun displayNameToPreset(name: String): EncoderPreset {
        return when (name.lowercase()) {
            "ultrafast" -> EncoderPreset.ULTRAFAST
            "superfast" -> EncoderPreset.SUPERFAST
            "veryfast" -> EncoderPreset.VERYFAST
            "faster" -> EncoderPreset.FASTER
            "fast" -> EncoderPreset.FAST
            "medium" -> EncoderPreset.MEDIUM
            "slow" -> EncoderPreset.SLOW
            "slower" -> EncoderPreset.SLOWER
            "veryslow" -> EncoderPreset.VERYSLOW
            else -> EncoderPreset.ULTRAFAST
        }
    }

    private fun setupClickListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnSave.setOnClickListener {
            saveSettings()
        }
        
        binding.btnRestoreDefaults.setOnClickListener {
            restoreDefaults()
        }
    }
    
    private fun restoreDefaults() {
        settings.restoreDefaults()
        loadSettings()  // Reload UI with default values
        Toast.makeText(this, R.string.defaults_restored, Toast.LENGTH_SHORT).show()
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
        settings.bondixEnabled = binding.switchBondixEnabled.isChecked
        settings.bondixForSrt = binding.switchBondixForSrt.isChecked
        settings.tunnelName = binding.inputTunnelName.text.toString().trim()
        settings.tunnelPassword = binding.inputTunnelPassword.text.toString()
        settings.endpointServer = binding.inputEndpointServer.text.toString().trim()

        // Save video settings
        settings.resolution = binding.inputResolution.text.toString()
        settings.videoBitrateKbps = videoBitrate
        settings.frameRate = binding.inputFramerate.text.toString().toIntOrNull() ?: 30

        // Save encoder settings
        settings.useHardwareEncoder = binding.switchHardwareEncoder.isChecked
        settings.encoderPreset = displayNameToPreset(binding.inputEncoderPreset.text.toString())
        settings.keyframeInterval = binding.inputKeyframeInterval.text.toString().toIntOrNull() ?: 2
        settings.bFrames = binding.inputBFrames.text.toString().toIntOrNull() ?: 0

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

