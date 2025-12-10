package com.orbistream

import android.app.Application
import android.util.Log
import com.orbistream.bondix.BondixManager
import com.orbistream.bondix.NetworkRegistry
import com.orbistream.data.SettingsRepository
import com.orbistream.streaming.NativeStreamer

/**
 * OrbiStreamApp is the Application class that initializes core components:
 * - NetworkRegistry for tracking available networks
 * - BondixManager for network bonding
 * 
 * The Bondix integration follows the libbondix documentation:
 * 1. Start NetworkRegistry to track WiFi/Cellular networks
 * 2. Initialize Bondix with socket binding callback
 * 3. Configure tunnel credentials, server, and proxy
 * 4. Update available interfaces
 * 5. Enable bonding mode
 */
class OrbiStreamApp : Application() {
    
    companion object {
        private const val TAG = "OrbiStreamApp"
        
        lateinit var instance: OrbiStreamApp
            private set
    }

    lateinit var settingsRepository: SettingsRepository
        private set
    
    lateinit var bondixManager: BondixManager
        private set
    
    private var bondixInitialized = false
    private var bondixConfigured = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        
        Log.i(TAG, "OrbiStream Application starting")
        
        // Initialize settings repository
        settingsRepository = SettingsRepository(this)
        
        // Initialize GStreamer
        initializeGStreamer()
        
        // Start network tracking
        initializeNetworkTracking()
        
        // Initialize Bondix
        initializeBondix()
    }

    private fun initializeGStreamer() {
        Log.d(TAG, "Initializing GStreamer")
        if (NativeStreamer.initGStreamer(this)) {
            Log.i(TAG, "GStreamer libraries loaded successfully")
            
            // Now initialize the native streaming engine
            if (NativeStreamer.initialize()) {
                Log.i(TAG, "NativeStreamer engine initialized successfully")
            } else {
                Log.e(TAG, "NativeStreamer engine initialization failed")
            }
        } else {
            Log.e(TAG, "GStreamer initialization failed")
        }
    }

    private fun initializeNetworkTracking() {
        Log.d(TAG, "Starting NetworkRegistry")
        NetworkRegistry.start(this)
        
        // Log available networks
        NetworkRegistry.addStatusListener(object : NetworkRegistry.NetworkStatusListener {
            override fun onNetworkStatusChanged(wifiAvailable: Boolean, cellularAvailable: Boolean) {
                Log.d(TAG, "Network status: WiFi=$wifiAvailable, Cellular=$cellularAvailable")
                
                // Update Bondix interfaces when network status changes
                if (bondixInitialized) {
                    bondixManager.updateInterfacesFromRegistry()
                }
            }
        })
    }

    private fun initializeBondix() {
        bondixManager = BondixManager(this)
        
        // Set up status listener
        bondixManager.setStatusListener(object : BondixManager.StatusListener {
            override fun onBondixStatusChanged(connected: Boolean, message: String) {
                Log.i(TAG, "Bondix status: connected=$connected, message=$message")
            }
        })
        
        // Try to initialize Bondix
        try {
            bondixInitialized = bondixManager.initialize()
            
            if (bondixInitialized) {
                Log.i(TAG, "Bondix initialized successfully")
                
                // Configure Bondix if settings are available
                if (settingsRepository.hasBondixSettings()) {
                    Log.i(TAG, "Bondix settings found, configuring tunnel...")
                    configureBondixIfReady()
                } else {
                    Log.w(TAG, "No Bondix settings configured - please set tunnel name, password, and server in Settings")
                }
            } else {
                Log.w(TAG, "Bondix initialization failed - library may not be included")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bondix initialization error: ${e.message}")
            bondixInitialized = false
        }
    }

    /**
     * Configure Bondix with saved settings.
     * Call this when settings are updated.
     * 
     * @param force Force reconfiguration even if already configured
     */
    fun configureBondixIfReady(force: Boolean = false) {
        if (!bondixInitialized) {
            Log.w(TAG, "Cannot configure Bondix - not initialized")
            return
        }

        if (!settingsRepository.hasBondixSettings()) {
            Log.d(TAG, "Bondix settings not configured yet")
            return
        }

        // Skip if already configured (unless forced)
        if (bondixConfigured && !force) {
            Log.d(TAG, "Bondix already configured - skipping (use force=true to reconfigure)")
            return
        }

        Log.i(TAG, "========================================")
        Log.i(TAG, "=== CONFIGURING BONDIX TUNNEL ===")
        Log.i(TAG, "Tunnel: ${settingsRepository.tunnelName}")
        Log.i(TAG, "Server: ${settingsRepository.endpointServer}")
        Log.i(TAG, "========================================")
        
        val success = bondixManager.configureAll(
            tunnelName = settingsRepository.tunnelName,
            tunnelPassword = settingsRepository.tunnelPassword,
            serverHost = settingsRepository.endpointServer
        )
        
        if (success) {
            bondixConfigured = true
            Log.i(TAG, "Bondix tunnel configured successfully")
        } else {
            Log.e(TAG, "Bondix tunnel configuration failed")
        }
    }
    
    /**
     * Check if Bondix has been configured.
     */
    fun isBondixConfigured(): Boolean = bondixConfigured

    /**
     * Check if Bondix is ready for streaming.
     */
    fun isBondixReady(): Boolean = bondixInitialized && bondixManager.isReady()

    /**
     * Check if all required settings are configured.
     */
    fun isReadyToStream(): Boolean = 
        settingsRepository.hasSrtSettings() && 
        (!bondixInitialized || settingsRepository.hasBondixSettings())

    override fun onTerminate() {
        super.onTerminate()
        Log.i(TAG, "OrbiStream Application terminating")
        
        // Cleanup
        NetworkRegistry.stop()
        bondixManager.shutdown()
    }
}

