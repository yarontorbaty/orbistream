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
     * This stub logs the command and returns a success response.
     */
    @JvmStatic
    fun configure(configJson: String): String {
        Log.d(TAG, "Stub configure: $configJson")
        return """{"status": "ok", "stub": true}"""
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

