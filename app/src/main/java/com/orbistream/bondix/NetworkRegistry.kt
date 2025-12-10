package com.orbistream.bondix

import android.content.Context
import android.net.*
import android.os.Build
import java.util.concurrent.ConcurrentHashMap

/**
 * NetworkRegistry tracks available Android networks (WiFi, Cellular, etc.)
 * and provides lookup functionality for Bondix socket binding.
 * 
 * This implementation follows the Bondix Android integration guide.
 */
object NetworkRegistry {
    private lateinit var cm: ConnectivityManager
    private val byIfName = ConcurrentHashMap<String, Network>()
    private val byTransport = ConcurrentHashMap<Int, Network>()

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    
    // Listeners for network status changes
    private val statusListeners = mutableListOf<NetworkStatusListener>()

    interface NetworkStatusListener {
        fun onNetworkStatusChanged(wifiAvailable: Boolean, cellularAvailable: Boolean)
    }

    fun addStatusListener(listener: NetworkStatusListener) {
        statusListeners.add(listener)
    }

    fun removeStatusListener(listener: NetworkStatusListener) {
        statusListeners.remove(listener)
    }

    private fun notifyStatusChanged() {
        val wifiAvailable = byTransport.containsKey(NetworkCapabilities.TRANSPORT_WIFI)
        val cellularAvailable = byTransport.containsKey(NetworkCapabilities.TRANSPORT_CELLULAR)
        statusListeners.forEach { it.onNetworkStatusChanged(wifiAvailable, cellularAvailable) }
    }

    fun start(ctx: Context) {
        cm = ctx.getSystemService(ConnectivityManager::class.java)

        refreshAll()

        val req = NetworkRequest.Builder().build()
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                update(network)
                notifyStatusChanged()
            }
            
            override fun onLinkPropertiesChanged(nw: Network, lp: LinkProperties) {
                update(nw)
                notifyStatusChanged()
            }
            
            override fun onLost(network: Network) {
                remove(network)
                notifyStatusChanged()
            }
        }
        cm.registerNetworkCallback(req, networkCallback!!)
    }

    fun stop() {
        networkCallback?.let {
            try {
                cm.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                // Ignore if already unregistered
            }
            networkCallback = null
        }
        byIfName.clear()
        byTransport.clear()
    }

    private fun refreshAll() {
        cm.allNetworks.forEach { update(it) }
    }

    private fun update(nw: Network) {
        val lp = cm.getLinkProperties(nw) ?: return
        val caps = cm.getNetworkCapabilities(nw)

        lp.interfaceName?.let { ifName ->
            byIfName[ifName] = nw
        }

        caps?.let {
            fun putIf(transport: Int) {
                if (it.hasTransport(transport)) byTransport[transport] = nw
            }
            putIf(NetworkCapabilities.TRANSPORT_WIFI)
            putIf(NetworkCapabilities.TRANSPORT_CELLULAR)
            putIf(NetworkCapabilities.TRANSPORT_ETHERNET)
            putIf(NetworkCapabilities.TRANSPORT_VPN)
        }
    }

    private fun remove(nw: Network) {
        byIfName.entries.removeIf { it.value == nw }
        byTransport.entries.removeIf { it.value == nw }
    }

    /**
     * Find a Network by ID. The ID can be:
     * - An interface name (e.g., "wlan0", "rmnet0")
     * - A transport type name (e.g., "WIFI", "CELLULAR", "MOBILE", "ETHERNET")
     */
    fun findNetwork(id: String): Network? {
        byIfName[id]?.let { return it }
        return when (id.uppercase()) {
            "WIFI" -> byTransport[NetworkCapabilities.TRANSPORT_WIFI]
            "CELLULAR", "MOBILE" -> byTransport[NetworkCapabilities.TRANSPORT_CELLULAR]
            "ETHERNET", "ETH" -> byTransport[NetworkCapabilities.TRANSPORT_ETHERNET]
            "VPN" -> byTransport[NetworkCapabilities.TRANSPORT_VPN]
            else -> null
        }
    }

    /**
     * Get the network handle for a given interface ID.
     * Returns 0 if not found or if API level is too low.
     */
    fun getHandleFor(id: String): Long {
        val nw = findNetwork(id) ?: return 0L
        return if (Build.VERSION.SDK_INT >= 29) nw.networkHandle else 0L
    }

    /**
     * Bind a Socket to the network identified by the given ID.
     */
    fun bindSocketById(id: String, socket: java.net.Socket): Boolean {
        val nw = findNetwork(id) ?: return false
        return try {
            nw.bindSocket(socket)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Bind a DatagramSocket to the network identified by the given ID.
     */
    fun bindDatagramById(id: String, ds: java.net.DatagramSocket): Boolean {
        val nw = findNetwork(id) ?: return false
        return try {
            nw.bindSocket(ds)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Check if WiFi network is available.
     */
    fun isWifiAvailable(): Boolean = byTransport.containsKey(NetworkCapabilities.TRANSPORT_WIFI)

    /**
     * Check if Cellular network is available.
     */
    fun isCellularAvailable(): Boolean = byTransport.containsKey(NetworkCapabilities.TRANSPORT_CELLULAR)

    /**
     * Get available interface IDs for Bondix configuration.
     */
    fun getAvailableInterfaceIds(): List<String> {
        val ids = mutableListOf<String>()
        if (isWifiAvailable()) ids.add("WIFI")
        if (isCellularAvailable()) ids.add("CELLULAR")
        return ids
    }
}

