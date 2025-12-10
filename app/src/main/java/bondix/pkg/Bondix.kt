package bondix.pkg

import android.content.Context
import android.util.Log

/**
 * Bondix JNI wrapper for the network bonding engine.
 * 
 * This object provides the Kotlin interface to the native libbondix library
 * which is loaded from the bondix-root-release.aar.
 * 
 * Per the Bondix integration docs, the JNI methods are:
 * - initialize(ctx: Context, binder: SocketBindCallback): Boolean
 * - configure(configJson: String): String
 * - shutdown()
 */
object Bondix {
    private const val TAG = "Bondix"
    
    init {
        try {
            System.loadLibrary("bondix")
            Log.i(TAG, "Bondix native library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load Bondix native library: ${e.message}")
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
    external fun initialize(ctx: Context, binder: SocketBindCallback): Boolean

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
    external fun configure(configJson: String): String

    /**
     * Shutdown the Bondix engine.
     * 
     * Can be called from any thread, exactly once.
     * Signals the Bondix thread to terminate and waits for completion.
     */
    @JvmStatic
    external fun shutdown()
}
