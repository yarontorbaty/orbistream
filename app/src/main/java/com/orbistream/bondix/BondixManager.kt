package com.orbistream.bondix

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * BondixManager handles the lifecycle and configuration of the Bondix bonding engine.
 * 
 * This class provides a high-level interface for:
 * - Initializing Bondix with network binding callbacks
 * - Configuring tunnel credentials and servers
 * - Managing the SOCKS5 proxy
 * - Updating available network interfaces
 */
class BondixManager(private val context: Context) {
    
    companion object {
        private const val TAG = "BondixManager"
        const val DEFAULT_PROXY_HOST = "127.0.0.1"
        const val DEFAULT_PROXY_PORT = 28007
    }

    private var isInitialized = false
    private var proxyPort = DEFAULT_PROXY_PORT

    /**
     * Status listener for Bondix state changes.
     */
    interface StatusListener {
        fun onBondixStatusChanged(connected: Boolean, message: String)
    }

    private var statusListener: StatusListener? = null

    fun setStatusListener(listener: StatusListener?) {
        statusListener = listener
    }

    /**
     * Initialize the Bondix engine.
     * Must be called before any configuration.
     * 
     * @return true if initialization succeeded
     */
    fun initialize(): Boolean {
        if (isInitialized) {
            Log.w(TAG, "Bondix already initialized")
            return true
        }

        return try {
            // Initialize Bondix with socket binding callback
            val success = bondix.pkg.Bondix.initialize(context) { id, fd ->
                SocketBinder.bindFdToNetwork(id, fd)
            }
            
            if (success) {
                isInitialized = true
                Log.i(TAG, "Bondix initialized successfully")
                statusListener?.onBondixStatusChanged(true, "Initialized")
            } else {
                Log.e(TAG, "Bondix initialization failed")
                statusListener?.onBondixStatusChanged(false, "Initialization failed")
            }
            
            success
        } catch (e: Exception) {
            Log.e(TAG, "Bondix initialization error: ${e.message}")
            statusListener?.onBondixStatusChanged(false, "Error: ${e.message}")
            false
        } catch (e: UnsatisfiedLinkError) {
            // Library not loaded - this is expected if AAR is not included
            Log.e(TAG, "Bondix library not found: ${e.message}")
            statusListener?.onBondixStatusChanged(false, "Library not found")
            false
        }
    }

    /**
     * Configure tunnel credentials.
     * 
     * @param tunnelName Bondix tunnel name
     * @param tunnelPassword Bondix tunnel password
     * @return Configuration response from Bondix
     */
    fun setTunnel(tunnelName: String, tunnelPassword: String): String {
        if (!isInitialized) {
            Log.e(TAG, "Bondix not initialized")
            return """{"error": "not initialized"}"""
        }

        val config = JSONObject().apply {
            put("target", "tunnel")
            put("action", "set-tunnel")
            put("name", tunnelName)
            put("password", tunnelPassword)
        }
        
        return configure(config.toString())
    }

    /**
     * Add a Bondix server endpoint.
     * 
     * @param host Server hostname or IP address
     * @return Configuration response from Bondix
     */
    fun addServer(host: String): String {
        if (!isInitialized) {
            Log.e(TAG, "Bondix not initialized")
            return """{"error": "not initialized"}"""
        }

        val config = JSONObject().apply {
            put("target", "tunnel")
            put("action", "add-server")
            put("host", host)
        }
        
        return configure(config.toString())
    }

    /**
     * Enable the local SOCKS5 proxy.
     * 
     * @param host Proxy bind host (usually 127.0.0.1)
     * @param port Proxy port
     * @return Configuration response from Bondix
     */
    fun enableProxy(host: String = DEFAULT_PROXY_HOST, port: Int = DEFAULT_PROXY_PORT): String {
        if (!isInitialized) {
            Log.e(TAG, "Bondix not initialized")
            return """{"error": "not initialized"}"""
        }

        this.proxyPort = port

        val config = JSONObject().apply {
            put("target", "tunnel")
            put("action", "enable-proxy-server")
            put("host", host)
            put("port", port.toString())
        }
        
        return configure(config.toString())
    }

    /**
     * Update available network interfaces.
     * 
     * @param interfaces Map of interface ID to display name
     * @return Configuration response from Bondix
     */
    fun updateInterfaces(interfaces: Map<String, String>): String {
        if (!isInitialized) {
            Log.e(TAG, "Bondix not initialized")
            return """{"error": "not initialized"}"""
        }

        val interfacesObj = JSONObject()
        interfaces.forEach { (id, name) ->
            interfacesObj.put(id, JSONObject().apply {
                put("name", name)
                put("preset", "speed")
            })
        }

        val config = JSONObject().apply {
            put("target", "tunnel")
            put("action", "update-interfaces")
            put("interfaces", interfacesObj)
        }
        
        return configure(config.toString())
    }

    /**
     * Update interfaces based on currently available networks.
     */
    fun updateInterfacesFromRegistry(): String {
        val interfaces = mutableMapOf<String, String>()
        
        if (NetworkRegistry.isWifiAvailable()) {
            interfaces["WIFI"] = "WiFi"
        }
        if (NetworkRegistry.isCellularAvailable()) {
            interfaces["CELLULAR"] = "Cellular"
        }
        
        if (interfaces.isEmpty()) {
            Log.w(TAG, "No network interfaces available")
            return """{"warning": "no interfaces available"}"""
        }
        
        return updateInterfaces(interfaces)
    }

    /**
     * Set the bonding preset.
     * 
     * @param preset Preset name (e.g., "bonding", "speed", "failover")
     * @return Configuration response from Bondix
     */
    fun setPreset(preset: String = "bonding"): String {
        if (!isInitialized) {
            Log.e(TAG, "Bondix not initialized")
            return """{"error": "not initialized"}"""
        }

        val config = JSONObject().apply {
            put("target", "tunnel")
            put("action", "set-preset")
            put("preset", preset)
        }
        
        return configure(config.toString())
    }

    /**
     * Send a raw configuration command to Bondix.
     * 
     * @param configJson JSON configuration string
     * @return Response from Bondix
     */
    fun configure(configJson: String): String {
        return try {
            val response = bondix.pkg.Bondix.configure(configJson)
            Log.d(TAG, "Configure: $configJson -> $response")
            response
        } catch (e: Exception) {
            Log.e(TAG, "Configure error: ${e.message}")
            """{"error": "${e.message}"}"""
        }
    }

    /**
     * Set JVM proxy properties to route traffic through Bondix.
     */
    fun enableJvmProxy() {
        System.setProperty("socksProxyHost", DEFAULT_PROXY_HOST)
        System.setProperty("socksProxyPort", proxyPort.toString())
        Log.i(TAG, "JVM SOCKS proxy enabled: $DEFAULT_PROXY_HOST:$proxyPort")
    }

    /**
     * Clear JVM proxy properties.
     */
    fun disableJvmProxy() {
        System.clearProperty("socksProxyHost")
        System.clearProperty("socksProxyPort")
        Log.i(TAG, "JVM SOCKS proxy disabled")
    }

    /**
     * Get the current proxy configuration for use with external libraries.
     */
    fun getProxyConfig(): Pair<String, Int> = Pair(DEFAULT_PROXY_HOST, proxyPort)

    /**
     * Shutdown the Bondix engine.
     * Should be called when the app is terminating.
     */
    fun shutdown() {
        if (!isInitialized) return
        
        try {
            disableJvmProxy()
            bondix.pkg.Bondix.shutdown()
            isInitialized = false
            Log.i(TAG, "Bondix shutdown complete")
            statusListener?.onBondixStatusChanged(false, "Shutdown")
        } catch (e: Exception) {
            Log.e(TAG, "Bondix shutdown error: ${e.message}")
        }
    }

    /**
     * Check if Bondix is initialized.
     */
    fun isReady(): Boolean = isInitialized

    /**
     * Perform a complete configuration with all settings.
     * 
     * @param tunnelName Bondix tunnel name
     * @param tunnelPassword Bondix tunnel password
     * @param serverHost Bondix server endpoint
     * @param proxyPort Local proxy port
     */
    fun configureAll(
        tunnelName: String,
        tunnelPassword: String,
        serverHost: String,
        proxyPort: Int = DEFAULT_PROXY_PORT
    ): Boolean {
        if (!isInitialized) {
            Log.e(TAG, "Bondix not initialized")
            return false
        }

        try {
            setTunnel(tunnelName, tunnelPassword)
            addServer(serverHost)
            enableProxy(port = proxyPort)
            updateInterfacesFromRegistry()
            setPreset("bonding")
            enableJvmProxy()
            
            statusListener?.onBondixStatusChanged(true, "Connected")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Configuration failed: ${e.message}")
            statusListener?.onBondixStatusChanged(false, "Configuration failed")
            return false
        }
    }
}

