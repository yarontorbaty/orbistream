package com.orbistream.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.orbistream.OrbiStreamApp
import com.orbistream.R
import com.orbistream.bondix.NetworkRegistry
import com.orbistream.databinding.ActivityMainBinding

/**
 * MainActivity is the entry point of the app.
 * 
 * It displays:
 * - Network status (WiFi, Cellular, Bondix)
 * - Start streaming button
 * - Settings button
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var app: OrbiStreamApp

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            startStreamingActivity()
        } else {
            Toast.makeText(this, R.string.error_camera_permission, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        app = OrbiStreamApp.instance
        
        setupClickListeners()
        setupNetworkStatusListener()
    }

    override fun onResume() {
        super.onResume()
        updateNetworkStatus()
        updateButtonState()
    }

    private fun setupClickListeners() {
        binding.btnStartStream.setOnClickListener {
            if (checkPermissions()) {
                startStreamingActivity()
            } else {
                requestPermissions()
            }
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun setupNetworkStatusListener() {
        NetworkRegistry.addStatusListener(object : NetworkRegistry.NetworkStatusListener {
            override fun onNetworkStatusChanged(wifiAvailable: Boolean, cellularAvailable: Boolean) {
                runOnUiThread {
                    updateNetworkStatus()
                }
            }
        })
    }

    private fun updateNetworkStatus() {
        // WiFi status
        val wifiAvailable = NetworkRegistry.isWifiAvailable()
        updateStatusIndicator(
            binding.wifiStatusIndicator,
            binding.wifiStatusText,
            getString(R.string.wifi_status, if (wifiAvailable) "Connected" else "Disconnected"),
            wifiAvailable
        )

        // Cellular status
        val cellularAvailable = NetworkRegistry.isCellularAvailable()
        updateStatusIndicator(
            binding.cellularStatusIndicator,
            binding.cellularStatusText,
            getString(R.string.cellular_status, if (cellularAvailable) "Connected" else "Disconnected"),
            cellularAvailable
        )

        // Bondix status
        val bondixReady = app.isBondixReady()
        val bondixStatus = when {
            !app.settingsRepository.hasBondixSettings() -> "Not configured"
            bondixReady -> "Ready"
            else -> "Unavailable"
        }
        updateStatusIndicator(
            binding.bondixStatusIndicator,
            binding.bondixStatusText,
            getString(R.string.bondix_status, bondixStatus),
            bondixReady
        )
    }

    private fun updateStatusIndicator(indicator: View, textView: android.widget.TextView, text: String, connected: Boolean) {
        textView.text = text
        
        val color = if (connected) {
            ContextCompat.getColor(this, R.color.status_connected)
        } else {
            ContextCompat.getColor(this, R.color.status_disconnected)
        }
        
        (indicator.background as? GradientDrawable)?.setColor(color)
    }

    private fun updateButtonState() {
        val canStream = app.settingsRepository.hasSrtSettings()
        binding.btnStartStream.isEnabled = canStream
        binding.btnStartStream.alpha = if (canStream) 1.0f else 0.5f
    }

    private fun checkPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        permissionLauncher.launch(requiredPermissions)
    }

    private fun startStreamingActivity() {
        if (!app.settingsRepository.hasSrtSettings()) {
            Toast.makeText(this, "Please configure SRT settings first", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }

        startActivity(Intent(this, StreamingActivity::class.java))
    }
}

