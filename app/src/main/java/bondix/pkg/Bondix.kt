package bondix.pkg

import android.content.Context
import android.util.Log

/**
 * Bondix JNI wrapper for the network bonding engine.
 * 
 * This object provides the Kotlin interface to the native libbondix library
 * which is loaded from the bondix-root-release.aar.
 */
object Bondix {
    private const val TAG = "Bondix"
    private var isInitialized = false
    private var nativeLibLoaded = false

    init {
        try {
            System.loadLibrary("bondix")
            nativeLibLoaded = true
            Log.i(TAG, "Bondix native library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load Bondix native library: ${e.message}")
            nativeLibLoaded = false
        }
    }

    /**
     * Initialize the Bondix engine.
     * 
     * Starts the Bondix engine in a dedicated internal thread.
     * Must be called before any configure() calls.
     * Should be called once per process.
     *
     * @param ctx Application context
     * @param binder Callback for binding sockets to networks
     * @return true on success, false on failure
     */
    @JvmStatic
    fun initialize(ctx: Context, binder: SocketBindCallback): Boolean {
        if (!nativeLibLoaded) {
            Log.e(TAG, "Cannot initialize: native library not loaded")
            return false
        }
        
        if (isInitialized) {
            Log.w(TAG, "Bondix already initialized")
            return true
        }

        return try {
            val result = nativeInitialize(ctx, binder)
            if (result) {
                isInitialized = true
                Log.i(TAG, "Bondix initialized successfully")
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Bondix initialization failed: ${e.message}")
            false
        }
    }

    /**
     * Execute a configuration command.
     * 
     * Executes a JSON-encoded command and returns a JSON string as reply.
     * Can be called from any thread.
     * Call is blocking until the engine processes the command.
     *
     * @param configJson JSON configuration string
     * @return JSON response from Bondix
     */
    @JvmStatic
    fun configure(configJson: String): String {
        if (!isInitialized) {
            Log.w(TAG, "Bondix not initialized, returning error")
            return """{"error": "not initialized"}"""
        }

        return try {
            nativeConfigure(configJson)
        } catch (e: Exception) {
            Log.e(TAG, "Configure failed: ${e.message}")
            """{"error": "${e.message}"}"""
        }
    }

    /**
     * Shutdown the Bondix engine.
     * 
     * Can be called from any thread, exactly once.
     * Signals the Bondix thread to terminate and waits for completion.
     */
    @JvmStatic
    fun shutdown() {
        if (!isInitialized) {
            return
        }

        try {
            nativeShutdown()
            isInitialized = false
            Log.i(TAG, "Bondix shutdown complete")
        } catch (e: Exception) {
            Log.e(TAG, "Shutdown failed: ${e.message}")
        }
    }

    /**
     * Check if Bondix is ready.
     */
    fun isReady(): Boolean = isInitialized && nativeLibLoaded

    // Native method declarations - implemented in libbondix.so
    private external fun nativeInitialize(ctx: Context, binder: SocketBindCallback): Boolean
    private external fun nativeConfigure(configJson: String): String
    private external fun nativeShutdown()
}

