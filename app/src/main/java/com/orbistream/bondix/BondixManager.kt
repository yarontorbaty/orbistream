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
                
                // Enable Bondix native logging to a file we can read
                val logFile = context.cacheDir.absolutePath + "/bondix.log"
                val logResult = enableNativeLogging(logFile)
                Log.i(TAG, "Bondix logging enabled to: $logFile -> $logResult")
                
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
     * Enable native Bondix logging to a file.
     * Per wiki: {"target": "system", "action": "set-log", "file": "...", "fileMode": "append"}
     */
    fun enableNativeLogging(logFile: String): String {
        if (!isInitialized) return """{"error": "not initialized"}"""
        
        val config = JSONObject().apply {
            put("target", "system")
            put("action", "set-log")
            put("file", logFile)
            put("fileMode", "append")
        }
        return configure(config.toString())
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

        // Local Android SDK docs say "enable-proxy-server" (wiki says "enable-proxy" but that returns unknown_action)
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
                // Don't set preset - the Android SDK doesn't seem to support them
                // and the tunnel connects fine without it
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
     * Connect/start the tunnel.
     * This initiates the actual connection to the Bondix server.
     * 
     * @return Configuration response from Bondix
     */
    fun connect(): String {
        if (!isInitialized) {
            Log.e(TAG, "Bondix not initialized")
            return """{"error": "not initialized"}"""
        }

        val config = JSONObject().apply {
            put("target", "tunnel")
            put("action", "connect")
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
     * Get tunnel status/metrics from Bondix.
     * 
     * Uses "status" and "get-interface" commands per wiki.
     * Returns basic status if stats aren't available.
     * 
     * @return BondixStats with current metrics, or null if unavailable
     */
    fun getStats(): BondixStats? {
        if (!isInitialized) return null

        return try {
            // Try to query tunnel status (wiki: "status" command)
            val statusConfig = JSONObject().apply {
                put("target", "tunnel")
                put("action", "status")
            }
            val statusResponse = configure(statusConfig.toString())
            
            // Try to query interface stats (wiki: "get-interface" with index)
            val ifaceConfig = JSONObject().apply {
                put("target", "tunnel")
                put("action", "get-interface")
                put("index", 0)
            }
            val ifaceResponse = configure(ifaceConfig.toString())
            
            // Check if commands were successful
            val statusJson = JSONObject(statusResponse)
            val ifaceJson = JSONObject(ifaceResponse)
            
            // If both return errors, stats aren't available
            if (statusJson.optString("result") == "error" && 
                ifaceJson.optString("result") == "error") {
                // Return basic connected status
                return BondixStats(
                    connected = true, // Bondix is initialized
                    tunnelState = "connected"
                )
            }
            
            parseStats(statusResponse, ifaceResponse)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get stats: ${e.message}")
            // Return basic status on error
            BondixStats(connected = isInitialized, tunnelState = "unknown")
        }
    }

    private fun parseStats(statusJson: String, ifaceJson: String): BondixStats {
        val stats = BondixStats()
        
        try {
            val status = JSONObject(statusJson)
            // Check "status" field (string) - Bondix returns {"status":"connected"} not {"connected":true}
            val tunnelStatus = status.optString("status", "")
            stats.connected = (tunnelStatus == "connected")
            stats.tunnelState = tunnelStatus.ifEmpty { "unknown" }
            stats.serverHost = status.optString("endpoint", "")
            
            // Parse channels array from status response
            val channels = status.optJSONArray("channels")
            if (channels != null) {
                for (i in 0 until channels.length()) {
                    val channel = channels.getJSONObject(i)
                    val ifaceId = channel.optString("interface", "unknown")
                    val channelStats = channel.optJSONObject("stats")
                    val currentStats = channelStats?.optJSONObject("current")
                    
                    val ifaceStats = InterfaceStats(
                        id = ifaceId,
                        name = channel.optString("name", ifaceId),
                        active = (channel.optString("status") == "connected"),
                        txBytes = channel.optLong("totalOutgoing", 0),
                        rxBytes = channel.optLong("totalIncoming", 0),
                        txBitrate = 0.0, // Calculate from bytes if needed
                        rxBitrate = 0.0,
                        rtt = (channelStats?.optDouble("currentLatency", 0.0) ?: 0.0) / 1000.0, // Convert μs to ms
                        loss = currentStats?.optDouble("receivedLost", 0.0) ?: 0.0
                    )
                    stats.interfaces[ifaceId] = ifaceStats
                    
                    // Update overall latency from first connected channel
                    if (ifaceStats.active && stats.latencyMs == 0.0) {
                        stats.latencyMs = ifaceStats.rtt
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing stats: ${e.message}")
        }
        
        return stats
    }

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
            Log.i(TAG, "--- Bondix Configuration Start ---")
            
            // Step 1: Set tunnel credentials
            val tunnelResult = setTunnel(tunnelName, tunnelPassword)
            Log.i(TAG, "1. set-tunnel: $tunnelResult")
            
            // Step 2: Add server endpoint
            val serverResult = addServer(serverHost)
            Log.i(TAG, "2. add-server: $serverResult")
            
            // Step 3: Update network interfaces (before enabling proxy)
            val ifaceResult = updateInterfacesFromRegistry()
            Log.i(TAG, "3. update-interfaces: $ifaceResult")
            
            // Step 4: Skip set-preset - Android SDK doesn't support it
            // val presetResult = setPreset("bonding")
            Log.i(TAG, "4. set-preset: SKIPPED (not supported on Android SDK)")
            
            // Step 5: Wait for tunnel to establish before enabling proxy
            Log.i(TAG, "5. Waiting for tunnel to establish...")
            Thread.sleep(100)
            
            // Step 6: Enable SOCKS5 proxy (AFTER tunnel is configured)
            val proxyResult = enableProxy(port = proxyPort)
            Log.i(TAG, "6. enable-proxy-server: $proxyResult")
            
            // Step 7: Wait a bit more for proxy to start listening
            Thread.sleep(5000)
            
            // Step 8: Enable JVM proxy for Java/Kotlin traffic
            enableJvmProxy()
            Log.i(TAG, "8. JVM proxy enabled: $DEFAULT_PROXY_HOST:$proxyPort")
            
            Log.i(TAG, "--- Bondix Configuration Complete ---")
            
            statusListener?.onBondixStatusChanged(true, "Connected")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Configuration failed: ${e.message}")
            statusListener?.onBondixStatusChanged(false, "Configuration failed")
            return false
        }
    }
}

/**
 * Bondix tunnel and interface statistics.
 */
data class BondixStats(
    var connected: Boolean = false,
    var tunnelState: String = "unknown",
    var serverHost: String = "",
    var latencyMs: Double = 0.0,
    var packetLoss: Double = 0.0,
    var interfaces: MutableMap<String, InterfaceStats> = mutableMapOf()
) {
    /**
     * Get total TX bitrate across all interfaces.
     */
    fun getTotalTxBitrate(): Double = interfaces.values.sumOf { it.txBitrate }
    
    /**
     * Get total RX bitrate across all interfaces.
     */
    fun getTotalRxBitrate(): Double = interfaces.values.sumOf { it.rxBitrate }
    
    /**
     * Get total TX bytes across all interfaces.
     */
    fun getTotalTxBytes(): Long = interfaces.values.sumOf { it.txBytes }
    
    /**
     * Get total RX bytes across all interfaces.
     */
    fun getTotalRxBytes(): Long = interfaces.values.sumOf { it.rxBytes }
    
    /**
     * Get number of active interfaces.
     */
    fun getActiveInterfaceCount(): Int = interfaces.values.count { it.active }
    
    /**
     * Format total bitrate as human-readable string.
     */
    fun getFormattedTxBitrate(): String {
        val bps = getTotalTxBitrate()
        return when {
            bps >= 1_000_000 -> String.format("%.1f Mbps", bps / 1_000_000)
            bps >= 1_000 -> String.format("%.0f Kbps", bps / 1_000)
            else -> String.format("%.0f bps", bps)
        }
    }
}

/**
 * Statistics for a single network interface.
 */
data class InterfaceStats(
    val id: String,
    val name: String,
    val active: Boolean = false,
    val txBytes: Long = 0,
    val rxBytes: Long = 0,
    val txBitrate: Double = 0.0,  // bits per second
    val rxBitrate: Double = 0.0,
    val rtt: Double = 0.0,        // milliseconds
    val loss: Double = 0.0        // percentage 0-100
) {
    fun getFormattedTxBitrate(): String {
        return when {
            txBitrate >= 1_000_000 -> String.format("%.1f Mbps", txBitrate / 1_000_000)
            txBitrate >= 1_000 -> String.format("%.0f Kbps", txBitrate / 1_000)
            else -> String.format("%.0f bps", txBitrate)
        }
    }
    
    fun getFormattedBytes(): String {
        val total = txBytes + rxBytes
        return when {
            total >= 1_000_000_000 -> String.format("%.1f GB", total / 1_000_000_000.0)
            total >= 1_000_000 -> String.format("%.1f MB", total / 1_000_000.0)
            total >= 1_000 -> String.format("%.0f KB", total / 1_000.0)
            else -> "$total B"
        }
    }
}

