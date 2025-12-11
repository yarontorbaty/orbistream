package com.orbistream.bondix

import android.os.ParcelFileDescriptor
import android.util.Log

/**
 * SocketBinder provides the callback implementation for Bondix to bind
 * socket file descriptors to specific Android networks.
 * 
 * This is used by Bondix's native code to ensure each socket uses
 * the correct network interface for bonding.
 */
object SocketBinder {
    private const val TAG = "SocketBinder"

    /**
     * Bind an existing native socket FD to the network identified by `id`.
     * Works on API 23+ using Network.bindSocket(FileDescriptor).
     *
     * This method is called from Bondix native code via JNI.
     *
     * @param id    The interface identifier (e.g., "WIFI", "CELLULAR")
     * @param fdInt Native file descriptor (int)
     * @return true on success, false otherwise
     */
    @JvmStatic
    fun bindFdToNetwork(id: String, fdInt: Int): Boolean {
        // Handle empty or localhost IDs - these don't need network binding
        // This is likely used for the local SOCKS5 proxy socket
        if (id.isBlank() || id.equals("localhost", ignoreCase = true) || id == "127.0.0.1") {
            Log.d(TAG, "Skipping bind for local/empty interface id: '$id', FD: $fdInt")
            return true  // Return success - no binding needed for localhost
        }
        
        val nw = NetworkRegistry.findNetwork(id)
        if (nw == null) {
            Log.w(TAG, "No network found for id: $id")
            return false
        }

        // Wrap the native FD in a ParcelFileDescriptor
        val pfd = ParcelFileDescriptor.adoptFd(fdInt)
        return try {
            nw.bindSocket(pfd.fileDescriptor)
            Log.d(TAG, "Successfully bound FD $fdInt to network $id")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind FD $fdInt to network $id: ${e.message}")
            false
        } finally {
            try {
                // Detach to keep the native FD alive (don't close it)
                pfd.detachFd()
            } catch (_: Exception) {
                // Ignore
            }
        }
    }
}

