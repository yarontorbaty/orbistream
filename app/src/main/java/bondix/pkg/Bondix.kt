package bondix.pkg

import android.content.Context
import android.util.Log

/**
 * Stub implementation of the Bondix JNI wrapper.
 * 
 * This file provides a fallback when the actual Bondix AAR is not included.
 * When the real bondix-root-release.aar is added to app/libs/, this stub
 * will be replaced by the actual implementation.
 * 
 * To use the real Bondix library:
 * 1. Download bondix-root-release.aar from https://releases.bondix.dev/android/
 * 2. Place it in app/libs/
 * 3. Rebuild the project
 */
object Bondix {
    private const val TAG = "Bondix"
    private var isInitialized = false
    private var bindCallback: SocketBindCallback? = null

    /**
     * Initialize the Bondix engine.
     * 
     * In the real implementation, this starts the Bondix engine in a dedicated thread.
     * This stub returns true to allow the app to function without the actual library.
     */
    @JvmStatic
    fun initialize(ctx: Context, binder: SocketBindCallback): Boolean {
        Log.w(TAG, "Using stub Bondix implementation. Install the real AAR for network bonding.")
        bindCallback = binder
        isInitialized = true
        return true
    }

    /**
     * Execute a configuration command.
     * 
     * In the real implementation, this sends JSON commands to the Bondix engine.
     * This stub logs the command and returns mock responses for testing.
     */
    @JvmStatic
    fun configure(configJson: String): String {
        Log.d(TAG, "Stub configure: $configJson")
        
        // Parse action to return appropriate mock response
        return try {
            val json = org.json.JSONObject(configJson)
            val action = json.optString("action", "")
            
            when (action) {
                "get-status" -> """{
                    "status": "ok",
                    "connected": true,
                    "state": "connected",
                    "server": "bondix.example.com",
                    "latency_ms": 45.5,
                    "packet_loss": 0.1
                }"""
                
                "get-interface-stats" -> """{
                    "status": "ok",
                    "interfaces": {
                        "WIFI": {
                            "name": "WiFi",
                            "active": true,
                            "tx_bytes": ${(System.currentTimeMillis() / 10) % 100000000},
                            "rx_bytes": ${(System.currentTimeMillis() / 10) % 50000000},
                            "tx_bitrate": ${2500000 + (Math.random() * 500000).toLong()},
                            "rx_bitrate": ${1000000 + (Math.random() * 200000).toLong()},
                            "rtt_ms": 35.0,
                            "loss": 0.05
                        },
                        "CELLULAR": {
                            "name": "Cellular",
                            "active": true,
                            "tx_bytes": ${(System.currentTimeMillis() / 15) % 50000000},
                            "rx_bytes": ${(System.currentTimeMillis() / 15) % 25000000},
                            "tx_bitrate": ${1500000 + (Math.random() * 300000).toLong()},
                            "rx_bitrate": ${800000 + (Math.random() * 100000).toLong()},
                            "rtt_ms": 55.0,
                            "loss": 0.2
                        }
                    }
                }"""
                
                else -> """{"status": "ok", "stub": true}"""
            }
        } catch (e: Exception) {
            """{"status": "ok", "stub": true}"""
        }
    }

    /**
     * Shutdown the Bondix engine.
     * 
     * In the real implementation, this stops the Bondix thread.
     */
    @JvmStatic
    fun shutdown() {
        Log.d(TAG, "Stub shutdown")
        isInitialized = false
        bindCallback = null
    }
}

