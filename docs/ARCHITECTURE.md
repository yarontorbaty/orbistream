# OrbiStream System Architecture

## Overview

OrbiStream is an Android live streaming application that captures video and audio, encodes them using GStreamer, and streams via SRT (Secure Reliable Transport) protocol. The key differentiator is **Bondix network bonding**, which combines WiFi and Cellular connections for reliable streaming in challenging network conditions.

## High-Level Architecture

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                            ORBISTREAM ANDROID APP                             │
├──────────────────────────────────────────────────────────────────────────────┤
│                                                                               │
│  ┌─────────────────────────────────────────────────────────────────────────┐ │
│  │                           UI LAYER (Kotlin)                              │ │
│  │  ┌──────────────┐  ┌──────────────────┐  ┌────────────────────────────┐ │ │
│  │  │ MainActivity │  │ StreamingActivity│  │    SettingsActivity        │ │ │
│  │  │  - Network   │  │  - Camera Preview│  │    - SRT Config            │ │ │
│  │  │    Status    │  │  - Live Stats    │  │    - Bondix Credentials    │ │ │
│  │  │  - Start     │  │  - Bondix Metrics│  │    - Video/Audio Settings  │ │ │
│  │  └──────────────┘  └────────┬─────────┘  └────────────────────────────┘ │ │
│  └─────────────────────────────┼───────────────────────────────────────────┘ │
│                                │                                              │
│  ┌─────────────────────────────┼───────────────────────────────────────────┐ │
│  │                     SERVICE LAYER (Kotlin)                               │ │
│  │  ┌──────────────────────────┴──────────────────────────┐                │ │
│  │  │              StreamingService                        │                │ │
│  │  │  - Foreground service for background streaming       │                │ │
│  │  │  - Manages pipeline lifecycle                        │                │ │
│  │  │  - Notification with stats                           │                │ │
│  │  └─────────────────────────┬────────────────────────────┘                │ │
│  │                            │                                              │ │
│  │  ┌─────────────┐  ┌────────┴────────┐  ┌─────────────────────────────┐   │ │
│  │  │CameraManager│  │  AudioCapture   │  │      NativeStreamer         │   │ │
│  │  │  (CameraX)  │  │ (AudioRecord)   │  │    (JNI Wrapper)            │   │ │
│  │  │  - YUV→NV21 │  │  - PCM S16LE    │  │  - Pipeline control         │   │ │
│  │  │  - 30fps    │  │  - 48kHz stereo │  │  - Stats polling            │   │ │
│  │  └──────┬──────┘  └────────┬────────┘  └─────────────┬───────────────┘   │ │
│  └─────────┼──────────────────┼──────────────────────────┼──────────────────┘ │
│            │                  │                          │                    │
│  ┌─────────┼──────────────────┼──────────────────────────┼──────────────────┐ │
│  │         │     NATIVE LAYER (C++ via JNI)              │                  │ │
│  │         ▼                  ▼                          ▼                  │ │
│  │  ┌─────────────────────────────────────────────────────────────────────┐ │ │
│  │  │                     GStreamer Pipeline                               │ │ │
│  │  │  ┌─────────┐   ┌─────────┐   ┌─────────┐   ┌─────────┐   ┌───────┐ │ │ │
│  │  │  │ appsrc  │──▶│videoconv│──▶│ x264enc │──▶│h264parse│──▶│       │ │ │ │
│  │  │  │ (video) │   │         │   │ (H.264) │   │         │   │       │ │ │ │
│  │  │  └─────────┘   └─────────┘   └─────────┘   └─────────┘   │       │ │ │ │
│  │  │                                                          │mpegtsmux│ │ │
│  │  │  ┌─────────┐   ┌─────────┐   ┌─────────┐   ┌─────────┐   │       │ │ │ │
│  │  │  │ appsrc  │──▶│audioconv│──▶│voaacenc │──▶│aacparse │──▶│       │ │ │ │
│  │  │  │ (audio) │   │         │   │  (AAC)  │   │         │   │       │ │ │ │
│  │  │  └─────────┘   └─────────┘   └─────────┘   └─────────┘   └───┬───┘ │ │ │
│  │  │                                                              │     │ │ │
│  │  │                                                          ┌───▼───┐ │ │ │
│  │  │                                                          │srtsink│ │ │ │
│  │  │                                                          └───┬───┘ │ │ │
│  │  └──────────────────────────────────────────────────────────────┼─────┘ │ │
│  └─────────────────────────────────────────────────────────────────┼───────┘ │
│                                                                    │         │
│  ┌─────────────────────────────────────────────────────────────────┼───────┐ │
│  │                    BONDIX INTEGRATION LAYER                     │       │ │
│  │  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┴─────┐ │ │
│  │  │  NetworkRegistry │  │  SocketBinder    │  │    BondixManager       │ │ │
│  │  │  - Track WiFi    │  │  - Bind FD to    │  │    - Initialize engine │ │ │
│  │  │  - Track Cell    │  │    Network       │  │    - Configure tunnel  │ │ │
│  │  │  - Callbacks     │  │  - JNI callback  │  │    - Get metrics       │ │ │
│  │  └────────┬─────────┘  └────────┬─────────┘  └──────────┬─────────────┘ │ │
│  │           │                     │                       │               │ │
│  │           ▼                     ▼                       ▼               │ │
│  │  ┌──────────────────────────────────────────────────────────────────┐  │ │
│  │  │                   libbondix (AAR Native Library)                  │  │ │
│  │  │  ┌────────────────────────────────────────────────────────────┐  │  │ │
│  │  │  │                    Bondix Engine (C++)                      │  │  │ │
│  │  │  │  - Socket binding via callback                              │  │  │ │
│  │  │  │  - Traffic bonding algorithm                                │  │  │ │
│  │  │  │  - SOCKS5 proxy server (127.0.0.1:28007)                   │  │  │ │
│  │  │  └────────────────────────────────────────────────────────────┘  │  │ │
│  │  └──────────────────────────────────────────────────────────────────┘  │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
│                                                                              │
└──────────────────────────────────────────────────────────────────────────────┘
                                       │
                                       │ SOCKS5 Proxy
                                       ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                         NETWORK LAYER                                         │
│                                                                               │
│    ┌─────────────────────────────────────────────────────────────────────┐   │
│    │                    Bondix Bonding Engine                             │   │
│    │                                                                      │   │
│    │  ┌─────────────┐                           ┌─────────────┐          │   │
│    │  │   WiFi      │◀──── Socket Binding ────▶│  Cellular   │          │   │
│    │  │  Interface  │      (per-socket)         │  Interface  │          │   │
│    │  │  (wlan0)    │                           │  (rmnet0)   │          │   │
│    │  └──────┬──────┘                           └──────┬──────┘          │   │
│    │         │                                         │                  │   │
│    │         │        Bonded Traffic Stream            │                  │   │
│    │         └─────────────────┬───────────────────────┘                  │   │
│    │                           │                                          │   │
│    └───────────────────────────┼──────────────────────────────────────────┘   │
│                                │                                              │
└────────────────────────────────┼──────────────────────────────────────────────┘
                                 │
                                 ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                         BONDIX CLOUD                                          │
│  ┌────────────────────────────────────────────────────────────────────────┐  │
│  │                    Bondix Endpoint Server                               │  │
│  │  - Receives bonded streams from multiple interfaces                     │  │
│  │  - Reassembles packets in correct order                                 │  │
│  │  - Forwards unified stream to destination                               │  │
│  └─────────────────────────────────┬──────────────────────────────────────┘  │
└────────────────────────────────────┼─────────────────────────────────────────┘
                                     │
                                     ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                         SRT DESTINATION                                       │
│  ┌────────────────────────────────────────────────────────────────────────┐  │
│  │                    SRT Ingest Server                                    │  │
│  │  - srt://server:9000?streamid=xxx                                       │  │
│  │  - Receives MPEG-TS over SRT                                            │  │
│  │  - CDN, Media Server, etc.                                              │  │
│  └────────────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────────┘
```

## Component Details

### 1. UI Layer

| Component | File | Responsibility |
|-----------|------|----------------|
| `MainActivity` | `ui/MainActivity.kt` | Home screen, network status display, navigation |
| `StreamingActivity` | `ui/StreamingActivity.kt` | Live streaming UI, camera preview, real-time stats |
| `SettingsActivity` | `ui/SettingsActivity.kt` | Configuration for SRT, Bondix, video/audio |

### 2. Service Layer

| Component | File | Responsibility |
|-----------|------|----------------|
| `StreamingService` | `streaming/StreamingService.kt` | Foreground service, manages streaming lifecycle |
| `CameraManager` | `streaming/CameraManager.kt` | CameraX integration, frame capture, YUV conversion |
| `AudioCapture` | `streaming/AudioCapture.kt` | AudioRecord wrapper, PCM capture |
| `NativeStreamer` | `streaming/NativeStreamer.kt` | JNI wrapper for native GStreamer pipeline |
| `SettingsRepository` | `data/SettingsRepository.kt` | SharedPreferences persistence |

### 3. Native Layer (C++)

| Component | File | Responsibility |
|-----------|------|----------------|
| `SrtStreamer` | `cpp/srt_streamer.cpp` | GStreamer pipeline management |
| `orbistream_jni` | `cpp/orbistream_jni.cpp` | JNI bindings, Java↔C++ bridge |

**GStreamer Pipeline:**
```
Video: appsrc → videoconvert → x264enc → h264parse → mpegtsmux
Audio: appsrc → audioconvert → voaacenc → aacparse  ↗
                                                     └→ srtsink
```

### 4. Bondix Integration Layer

| Component | File | Responsibility |
|-----------|------|----------------|
| `NetworkRegistry` | `bondix/NetworkRegistry.kt` | Tracks available networks via ConnectivityManager |
| `SocketBinder` | `bondix/SocketBinder.kt` | Binds socket FDs to specific Android networks |
| `BondixManager` | `bondix/BondixManager.kt` | High-level Bondix API, configuration, metrics |

### 5. External Libraries

| Library | Source | Purpose |
|---------|--------|---------|
| `libbondix` | `app/libs/bondix-root-release.aar` | Network bonding engine |
| GStreamer | External SDK | Media encoding and SRT streaming |
| CameraX | AndroidX | Camera capture abstraction |

## Data Flow

### Streaming Data Flow

```
1. Camera Frame Capture
   CameraX → ImageProxy → YUV_420_888 → NV21 conversion → ByteArray

2. Audio Sample Capture  
   AudioRecord → PCM S16LE → ByteArray

3. Native Processing
   ByteArray → JNI → GstBuffer → appsrc → GStreamer pipeline

4. Encoding
   Video: NV21 → H.264 (x264enc)
   Audio: PCM → AAC (voaacenc)

5. Muxing & Transport
   H.264 + AAC → MPEG-TS → SRT → Bondix SOCKS5 proxy

6. Network Bonding
   Bondix splits traffic across WiFi + Cellular → Endpoint server
```

### Bondix Configuration Flow

```
1. App Start (OrbiStreamApp.onCreate)
   └─→ NetworkRegistry.start() - Begin tracking networks
   └─→ BondixManager.initialize() - Start Bondix engine with callback

2. Network Change
   └─→ NetworkCallback.onAvailable/onLost
   └─→ NetworkRegistry updates maps
   └─→ BondixManager.updateInterfacesFromRegistry()

3. Socket Binding (called from native Bondix)
   └─→ SocketBindCallback.bindFdToNetwork(id, fd)
   └─→ SocketBinder.bindFdToNetwork()
   └─→ NetworkRegistry.findNetwork(id)
   └─→ Network.bindSocket(FileDescriptor)

4. Metrics Query
   └─→ BondixManager.getStats()
   └─→ Bondix.configure({"action":"get-status"})
   └─→ Bondix.configure({"action":"get-interface-stats"})
   └─→ Parse JSON → BondixStats
```

## Stub Implementations

The project includes **conditional stubs** for development without all dependencies:

### GStreamer Stub (Native)

When GStreamer SDK is not installed, the native code compiles with `GSTREAMER_AVAILABLE=0`:

```cpp
// In CMakeLists.txt
if(EXISTS "${GSTREAMER_ROOT}/lib/gstreamer-1.0")
    set(GSTREAMER_AVAILABLE TRUE)
else()
    set(GSTREAMER_AVAILABLE FALSE)  // Stub mode
endif()
```

In stub mode:
- Pipeline creation returns success but does nothing
- Frame/audio push just counts bytes
- Stats return mock data

**To enable real GStreamer:**
1. Download GStreamer Android SDK
2. Set `GSTREAMER_ROOT_ANDROID` environment variable
3. Rebuild native code

### Bondix Library

The Bondix AAR (`app/libs/bondix-root-release.aar`) is included and provides:
- `bondix.pkg.Bondix` - JNI wrapper
- `bondix.pkg.SocketBindCallback` - Callback interface
- Native `libbondix.so` - Bonding engine

No stub needed - the real library is present.

## Threading Model

```
┌─────────────────────────────────────────────────────────────────┐
│                        THREADS                                   │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  Main Thread (UI)                                                │
│  └─ Activity lifecycle, UI updates, user interaction            │
│                                                                  │
│  CameraX Executor                                                │
│  └─ Frame capture, YUV→NV21 conversion                          │
│                                                                  │
│  Audio Capture Thread (Coroutine/IO)                             │
│  └─ AudioRecord.read() loop                                      │
│                                                                  │
│  GStreamer Main Loop Thread                                      │
│  └─ Pipeline message handling, state changes                     │
│                                                                  │
│  Bondix Engine Thread                                            │
│  └─ Internal to libbondix, handles bonding logic                │
│                                                                  │
│  Stats Polling (Coroutine)                                       │
│  └─ Periodic stats queries, UI updates                          │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

## Configuration Schema

### SRT Settings
```json
{
  "srtHost": "ingest.example.com",
  "srtPort": 9000,
  "streamId": "live/mystream",
  "passphrase": "optional-encryption-key"
}
```

### Bondix Settings
```json
{
  "tunnelName": "my-tunnel-id",
  "tunnelPassword": "tunnel-secret",
  "endpointServer": "bondix-endpoint.example.com"
}
```

### Video Settings
| Setting | Range | Default |
|---------|-------|---------|
| Resolution | 480p - 4K | 1080p |
| Bitrate | 500 - 50000 kbps | 4000 kbps |
| Frame Rate | 24 - 60 fps | 30 fps |

### Audio Settings
| Setting | Options | Default |
|---------|---------|---------|
| Bitrate | 64 - 320 kbps | 128 kbps |
| Sample Rate | 44100, 48000 Hz | 48000 Hz |
| Channels | Stereo | 2 |

## Bondix Commands Reference

| Command | Action | Purpose |
|---------|--------|---------|
| `set-tunnel` | Configure credentials | Set tunnel name and password |
| `add-server` | Add endpoint | Specify Bondix server host |
| `enable-proxy-server` | Start SOCKS5 | Enable local proxy on 127.0.0.1:28007 |
| `update-interfaces` | Configure networks | Define available interfaces (WIFI, CELLULAR) |
| `set-preset` | Set mode | Choose bonding strategy (bonding, speed, failover) |
| `get-status` | Query state | Get tunnel connection status, latency, loss |
| `get-interface-stats` | Query metrics | Per-interface TX/RX bytes, bitrate, RTT |

## Security Considerations

1. **SRT Encryption**: Optional passphrase-based encryption
2. **Bondix Tunnel**: Credentials stored in SharedPreferences (consider encryption)
3. **Network Security Config**: Cleartext only allowed for localhost (Bondix proxy)
4. **Permissions**: Camera, Microphone, Network access required

## Build Variants

| Variant | GStreamer | Bondix | Use Case |
|---------|-----------|--------|----------|
| Debug (no GStreamer) | Stub | Real AAR | UI development |
| Debug (with GStreamer) | Real | Real AAR | Full testing |
| Release | Real | Real AAR | Production |

## Performance Targets

| Metric | Target |
|--------|--------|
| Video latency | < 500ms |
| Audio latency | < 200ms |
| CPU usage | < 30% (encoding) |
| Memory | < 200MB |
| Battery | Foreground service optimized |

