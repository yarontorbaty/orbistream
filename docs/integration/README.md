# Bondix Android Client Library (libbondix)

## Overview
libbondix embeds the Bondix bonding engine in an Android app and exposes a minimal native API via JNI:

```
 initialize(ctx: Context, binder: SocketBindCallback): Boolean
 configure(configJson: String): String
 shutdown()
```
Instead of a TUN interface, Bondix runs a local SOCKS5 proxy (TCP + UDP).
Java/Kotlin traffic can be routed over this proxy using standard JVM proxy properties.

## Library Artifact

Prebuilt Android AAR: https://releases.bondix.dev/android/bondix-root-release.aar

Typical Gradle setup (example):
```
dependencies {
     implementation files("libs/bondix-root-release.aar")
 }
```

Adjust the path as needed.

## Permissions

Your AndroidManifest.xml must include:

 `<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>`

This is required for network discovery (ConnectivityManager) and NetworkRegistry.

## Public API
### SocketBindCallback
```
package bondix.pkg

fun interface SocketBindCallback {
    /**
     * Called by native code to bind a socket FD to a specific interface.
     *
     * @param id    User-defined interface identifier (opaque string).
     * @param fdInt Native file descriptor (int).
     * @return true on success, false otherwise.
     */
    fun bindFdToNetwork(id: String, fdInt: Int): Boolean
}
```

`id` is an opaque string defined by your app (see Interface Identifiers).

The callback must perform the actual binding of the FD to an `android.net.Network`, or whatever strategy you choose.

### Bondix JNI Wrapper
```
package bondix.pkg

import android.content.Context

object Bondix {
    init { System.loadLibrary("bondix") }

    @JvmStatic external fun initialize(ctx: Context, binder: SocketBindCallback): Boolean
    @JvmStatic external fun configure(configJson: String): String
    @JvmStatic external fun shutdown()
}
```

`initialize(ctx, binder): Boolean`

Starts the Bondix engine in a dedicated internal thread.

Must be called before any configure() calls.

Should be called once per process.

Returns true on success, false on failure.

`configure(configJson): String`

Executes a JSON-encoded command and returns a JSON string as reply.

Can be called from any thread.

Call is blocking until the engine processes the command.

The JSON schema is the same as the Bondix config/command system, with commands like set-tunnel, add-server, etc.

`shutdown()`

Can be called from any thread, exactly once.

Signals the Bondix thread to terminate and waits for completion.

## SOCKS5 Proxy Usage

libbondix runs a local SOCKS5 proxy capable of TCP and UDP.

Recommended configuration (via configure()):
```
{
  "target": "tunnel",
  "action": "enable-proxy-server",
  "host": "127.0.0.1",
  "port": "28007"
}
```

On the JVM side (Android app), you can route traffic through this proxy:

```
System.setProperty("socksProxyHost", "127.0.0.1")
System.setProperty("socksProxyPort", "28007")
```

Any libraries honoring standard JVM proxy properties will then use Bondix.

## Network Handling on Android
### NetworkRegistry

Example implementation:
```
package com.example.net

import android.content.Context
import android.net.*
import android.os.Build
import java.util.concurrent.ConcurrentHashMap

object NetworkRegistry {
    private lateinit var cm: ConnectivityManager
    private val byIfName = ConcurrentHashMap<String, Network>()
    private val byTransport = ConcurrentHashMap<Int, Network>()

    fun start(ctx: Context) {
        cm = ctx.getSystemService(ConnectivityManager::class.java)

        refreshAll()

        val req = NetworkRequest.Builder().build()
        cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = update(network)
            override fun onLinkPropertiesChanged(nw: Network, lp: LinkProperties) = update(nw)
            override fun onLost(network: Network) = remove(network)
        })
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

    fun getHandleFor(id: String): Long {
        val nw = findNetwork(id) ?: return 0L
        return if (Build.VERSION.SDK_INT >= 29) nw.networkHandle else 0L
    }

    fun bindSocketById(id: String, socket: java.net.Socket): Boolean {
        val nw = findNetwork(id) ?: return false
        return try {
            nw.bindSocket(socket); true
        } catch (_: Exception) {
            false
        }
    }

    fun bindDatagramById(id: String, ds: java.net.DatagramSocket): Boolean {
        val nw = findNetwork(id) ?: return false
        return try {
            nw.bindSocket(ds); true
        } catch (_: Exception) {
            false
        }
    }
}
```

Usage:

Call NetworkRegistry.start(applicationContext) once (e.g. in Application.onCreate()).

Use NetworkRegistry.findNetwork(id) inside your SocketBindCallback to map an interface ID to a Network.

### Interface Identifiers (IDs)

Bondix treats interface IDs as opaque strings.

Your app chooses the IDs and uses them consistently in:

JSON config (e.g. update-interfaces)

SocketBindCallback (the same ID is passed back from native).

Example IDs:

"WIFI", "CELLULAR", "ETH0", "MY_CUSTOM_LINK_1", etc.

There is no required or canonical naming scheme enforced by Bondix.

### Example SocketBindCallback Implementation

```
object SocketBinder {
    /**
     * Bind an existing native socket FD to the network identified by `id`.
     * Works on API 23+ using Network.bindSocket(FileDescriptor).
     *
     * @return true on success
     */
    @JvmStatic
    fun bindFdToNetwork(id: String, fdInt: Int): Boolean {
        val nw = NetworkRegistry.findNetwork(id) ?: return false

        val pfd = android.os.ParcelFileDescriptor.adoptFd(fdInt)
        return try {
            nw.bindSocket(pfd.fileDescriptor)
            true
        } catch (_: Exception) {
            false
        } finally {
            try { pfd.detachFd() } catch (_: Exception) { /* keep native fd alive */ }
        }
    }
}
```

Wiring it into Bondix.initialize:

```
val ok = Bondix.initialize(appContext) { id, fd ->
    SocketBinder.bindFdToNetwork(id, fd)
}
```

## Configuration Commands (Android Use)

Below are key commands relevant to Android. A more comprehensive list of all client
configuration commands can be found here: https://wiki.bondix.dev/wiki/Client_Configuration

*Note: not all commands in our wiki are currently implemented in libbondix for Android.*

### Set Tunnel Credentials
```
{
  "target": "tunnel",
  "action": "set-tunnel",
  "name": "TUNNELNAME",
  "password": "TUNNELPASSWORD"
}
```

Tunnel credentials may only be used by one client at a time.
For parallel clients you need separate tunnels.

### Add Server Endpoint
```
{
  "target": "tunnel",
  "action": "add-server",
  "host": "ENDPOINTSERVER"
}
```

### Enable Proxy
```
{
  "target": "tunnel",
  "action": "enable-proxy-server",
  "host": "127.0.0.1",
  "port": "28007"
}
```

### Update Interfaces

Your app decides the IDs and what they mean:

```
{
  "target": "tunnel",
  "action": "update-interfaces",
  "interfaces": {
    "CELLULAR": { "name": "Cellular", "preset": "speed" },
    "WIFI":     { "name": "WiFi",     "preset": "speed" }
  }
}
```


The IDs "CELLULAR" and "WIFI" here are the same IDs passed later into SocketBindCallback.

`name` is a human-readable name, shown i.e. in backend server. 

### Set Preset
```
{
  "target": "tunnel",
  "action": "set-preset",
  "preset": "bonding"
}
```

## Example Initialization Flow (Android)

High-level Kotlin sketch:
```
class App : Application() {
    override fun onCreate() {
        super.onCreate()

        // Track networks
        NetworkRegistry.start(this)

        // Start Bondix
        val ok = Bondix.initialize(this) { id, fd ->
            SocketBinder.bindFdToNetwork(id, fd)
        }
        if (!ok) {
            // handle error
            return
        }

        // Configure tunnel
        Bondix.configure("""{"target":"tunnel","action":"set-tunnel","name":"TUNNELNAME","password":"TUNNELPASSWORD"}""")
        Bondix.configure("""{"target":"tunnel","action":"add-server","host":"ENDPOINTSERVER"}""")
        Bondix.configure("""{"target":"tunnel","action":"enable-proxy-server","host":"127.0.0.1","port":"28007"}""")
        Bondix.configure(
            """
            {
              "target":"tunnel",
              "action":"update-interfaces",
              "interfaces":{
                "CELLULAR":{"name":"Cellular","preset":"speed"},
                "WIFI":{"name":"WiFi","preset":"speed"}
              }
            }
            """.trimIndent()
        )
        Bondix.configure("""{"target":"tunnel","action":"set-preset","preset":"bonding"}""")

        // Then configure JVM proxy properties where needed
        System.setProperty("socksProxyHost", "127.0.0.1")
        System.setProperty("socksProxyPort", "28007")
    }
}
```

Shutdown on app exit / when appropriate:

```
Bondix.shutdown()
```

## VPN Implementation
In principle, building a full VPN service on Android would follow the exact 
same pattern as the current SOCKS5-based integration: the Bondix engine would
expects callbacks for creating and managing a TUN interface, and the app would
implement those callbacks using Android’s VpnService APIs.