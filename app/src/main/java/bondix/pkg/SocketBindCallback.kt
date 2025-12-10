package bondix.pkg

/**
 * Callback interface for binding socket file descriptors to specific network interfaces.
 * 
 * This interface is called by Bondix native code to bind sockets to the correct
 * Android network (WiFi, Cellular, etc.) for network bonding.
 */
fun interface SocketBindCallback {
    /**
     * Called by native code to bind a socket FD to a specific interface.
     *
     * @param id    User-defined interface identifier (opaque string, e.g., "WIFI", "CELLULAR").
     * @param fdInt Native file descriptor (int).
     * @return true on success, false otherwise.
     */
    fun bindFdToNetwork(id: String, fdInt: Int): Boolean
}

