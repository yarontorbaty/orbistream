# Bondix Android SDK Integration Notes

## Overview

This document describes issues encountered while integrating the Bondix Android SDK (libbondix) for network bonding in a video streaming application, along with the solutions implemented.

**SDK Version:** `1.25.9-202512111410-dev`  
**Platform:** Android (API 24+)  
**Use Case:** Bonding WiFi + Cellular for reliable video streaming over UDP/SRT

---

## Issue 1: SOCKS5 Proxy Not Starting

### Symptom

After calling `enable-proxy-server`, the SOCKS5 proxy on port 28007 was not listening, resulting in `ECONNREFUSED` errors when attempting to connect.

```
Socks5UdpRelay E SOCKS5 connection error: failed to connect to /127.0.0.1 (port 28007) ... ECONNREFUSED
```

### Root Cause

The Bondix native library calls the `SocketBindCallback.bindFdToNetwork(id, fd)` callback with an **empty string** as the interface ID when binding the local SOCKS5 proxy socket.

Our implementation was returning `false` for empty IDs because no Android `Network` could be found:

```kotlin
fun bindFdToNetwork(id: String, fdInt: Int): Boolean {
    val nw = NetworkRegistry.findNetwork(id)  // Returns null for empty ID
    if (nw == null) {
        Log.w(TAG, "No network found for id: $id")
        return false  // ← This prevented the proxy from starting
    }
    // ... binding logic
}
```

### Log Evidence

```
12-11 17:20:26.055 W SocketBinder: No network found for id: 
```

Note the empty string after "id:".

### Solution

Return `true` for empty, localhost, or loopback interface IDs since these sockets don't need to be bound to a specific network interface:

```kotlin
fun bindFdToNetwork(id: String, fdInt: Int): Boolean {
    // Handle empty or localhost IDs - these don't need network binding
    // This is used for the local SOCKS5 proxy socket
    if (id.isBlank() || id.equals("localhost", ignoreCase = true) || id == "127.0.0.1") {
        Log.d(TAG, "Skipping bind for local/empty interface id: '$id', FD: $fdInt")
        return true  // Return success - no binding needed for localhost
    }
    
    val nw = NetworkRegistry.findNetwork(id)
    if (nw == null) {
        Log.w(TAG, "No network found for id: $id")
        return false
    }
    // ... rest of binding logic
}
```

### Result After Fix

```
12-11 17:30:37.582 D SocketBinder: Skipping bind for local/empty interface id: '', FD: 88
12-11 17:30:37.885 D SocketBinder: Successfully bound FD 89 to network CELLULAR
12-11 17:30:37.885 D SocketBinder: Successfully bound FD 91 to network WIFI
```

```bash
$ netstat -tln | grep 28007
tcp  0  0  127.0.0.1:28007  0.0.0.0:*  LISTEN
```

### Recommendation for Bondix

Consider documenting this behavior or passing a recognizable ID (e.g., "LOCAL" or "PROXY") instead of an empty string for the proxy socket, so integrators know to handle this case.

---

## Issue 2: Preset Configuration Warnings

### Symptom

Native Bondix logs showed warnings when configuring channel presets:

```
WRN| Could not apply channel preset speed
WRN| Could not apply tunnel preset, default preset not found!
```

### Context

We were sending `update-interfaces` with preset values:

```json
{
  "target": "tunnel",
  "action": "update-interfaces",
  "interfaces": {
    "CELLULAR": { "name": "Cellular", "preset": "speed" },
    "WIFI": { "name": "WiFi", "preset": "speed" }
  }
}
```

And calling `set-preset`:

```json
{
  "target": "tunnel",
  "action": "set-preset",
  "preset": "bonding"
}
```

### Solution

We removed the preset fields from `update-interfaces` and removed the `set-preset` call entirely. The tunnel still works correctly with default settings.

### Question for Bondix

1. What presets are available in the Android SDK? (e.g., "speed", "bonding", "mobile", "ethernet")
2. Is `set-preset` supported in the Android SDK, or is it only for desktop clients?

---

## Issue 3: Status Response Field Names

### Observation

The status response from `{"target":"tunnel","action":"status"}` uses different field names than expected:

| Expected (from some docs) | Actual Response |
|---------------------------|-----------------|
| `"connected": true` (boolean) | `"status": "connected"` (string) |
| `"state": "..."` | `"status": "connected"` |
| `"server": "..."` | `"endpoint": "34.206.43.133:443"` |
| `"interfaces": {...}` (object) | `"channels": [...]` (array) |

### Actual Status Response Structure

```json
{
  "action": "status",
  "activeProxyConnections": 0,
  "build": "1.25.9-202512111410-dev",
  "channelConnected": 2,
  "channels": [
    {
      "connectionAttempts": 0,
      "connectionType": "UDP",
      "enabled": true,
      "interface": "CELLULAR",
      "lastError": "No Error",
      "name": "Cellular",
      "stats": {
        "current": { "received": 1, "receivedLost": 0, "sent": 1, "sentAcked": 1, "sentLost": 0 },
        "currentLatency": 116843,
        "idleLatency": 113409,
        "inTransit": 0,
        "total": { "received": 26, "receivedLost": 0, "sent": 26, "sentAcked": 26, "sentLost": 0 }
      },
      "status": "connected",
      "totalIncoming": 416,
      "totalOutgoing": 0,
      "uptime": 39
    },
    {
      "interface": "WIFI",
      "name": "WiFi",
      "status": "connected",
      ...
    }
  ],
  "clientIp": "169.254.19.2/255.255.255.0",
  "endpoint": "34.206.43.133:443",
  "result": "ok",
  "status": "connected",
  "tunnel": "gcdemo",
  "uptime": 40,
  "version": "1.25.9-202512111410"
}
```

### Our Parsing Code

```kotlin
val tunnelStatus = status.optString("status", "")
stats.connected = (tunnelStatus == "connected")
stats.serverHost = status.optString("endpoint", "")

val channels = status.optJSONArray("channels")
if (channels != null) {
    for (i in 0 until channels.length()) {
        val channel = channels.getJSONObject(i)
        val ifaceId = channel.optString("interface", "unknown")
        val channelStats = channel.optJSONObject("stats")
        // ... parse per-channel stats
    }
}
```

---

## Working Configuration Sequence

Here is the configuration sequence that successfully establishes the tunnel and SOCKS5 proxy:

```kotlin
// 1. Initialize Bondix
Bondix.initialize(context) { id, fd ->
    SocketBinder.bindFdToNetwork(id, fd)
}

// 2. Set tunnel credentials
Bondix.configure("""{"target":"tunnel","action":"set-tunnel","name":"$tunnelName","password":"$password"}""")

// 3. Add server endpoint
Bondix.configure("""{"target":"tunnel","action":"add-server","host":"$serverHost"}""")

// 4. Update interfaces (without preset)
Bondix.configure("""
{
  "target":"tunnel",
  "action":"update-interfaces",
  "interfaces":{
    "CELLULAR":{"name":"Cellular"},
    "WIFI":{"name":"WiFi"}
  }
}
""")

// 5. Enable SOCKS5 proxy
Bondix.configure("""{"target":"tunnel","action":"enable-proxy-server","host":"127.0.0.1","port":"28007"}""")

// 6. Configure JVM proxy (for Java/Kotlin network calls)
System.setProperty("socksProxyHost", "127.0.0.1")
System.setProperty("socksProxyPort", "28007")
```

---

## Final Status

After implementing the fixes above:

| Component | Status |
|-----------|--------|
| Bondix Initialize | ✅ Success |
| Tunnel Connection | ✅ Connected to endpoint |
| SOCKS5 Proxy | ✅ Listening on 127.0.0.1:28007 |
| WiFi Channel | ✅ Connected, sending/receiving |
| Cellular Channel | ✅ Connected, sending/receiving |
| Client IP | ✅ 169.254.19.2 assigned |

---

## Environment

- **Device:** Android Emulator (API 34) and Physical Device (Android 13)
- **NDK:** r26d
- **Bondix AAR:** `libbondix-release.aar` (2024-12-11 build)
- **Networks:** WiFi + Cellular (emulated/real)

---

## Contact

For questions about this integration, please contact the development team.

