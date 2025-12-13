# iOS Port of Orbistream - Development Prompt

## Overview

Create an iOS version of the Orbistream Android app - a live video streaming application that uses GStreamer and SRT protocol. The Android reference implementation is located at:

```
/Users/yarontorbaty/Documents/Code/orbistream
```

## Reference Architecture

### Android Codebase Structure
```
app/src/main/
├── java/com/orbistream/
│   ├── ui/
│   │   ├── MainActivity.kt          # Main entry, permissions
│   │   ├── StreamingActivity.kt     # Live streaming UI with stats
│   │   └── SettingsActivity.kt      # Settings configuration
│   ├── streaming/
│   │   ├── StreamingService.kt      # Foreground service, pipeline management
│   │   ├── NativeStreamer.kt        # Kotlin interface to native GStreamer
│   │   └── CameraManager.kt         # Camera capture (CameraX)
│   ├── data/
│   │   └── SettingsRepository.kt    # SharedPreferences wrapper
│   └── bondix/                       # Network bonding (STUB FOR iOS)
│       ├── BondixManager.kt
│       ├── Socks5UdpRelay.kt
│       └── ...
├── jni/
│   ├── srt_streamer.h               # Native streamer header
│   ├── srt_streamer.cpp             # GStreamer pipeline implementation
│   └── orbistream_jni.cpp           # JNI bridge
└── res/
    └── layout/                       # XML layouts
```

## Core Features to Implement

### 1. GStreamer Integration
- Use GStreamer iOS SDK from https://gstreamer.freedesktop.org/download/
- Implement native pipeline equivalent to `srt_streamer.cpp`:
  - **Pipeline**: `appsrc` → encoder → `mpegtsmux` → `srtsink`
  - **Hardware encoding**: Use VideoToolbox (`vtenc_h264`) when available, fallback to `x264enc`
  - **Audio**: AAC encoding via `avenc_aac` or `faac`
  - **SRT output**: Use `srtsink` element with latency and streamid support

Reference the Android native implementation:
- `/Users/yarontorbaty/Documents/Code/orbistream/app/src/main/jni/srt_streamer.cpp`
- `/Users/yarontorbaty/Documents/Code/orbistream/app/src/main/jni/srt_streamer.h`

### 2. Camera Capture
- Use `AVFoundation` (`AVCaptureSession`, `AVCaptureVideoDataOutput`)
- Support resolutions: 480p, 720p, 1080p
- Convert camera frames to format suitable for GStreamer `appsrc`
- Implement frame callback similar to Android's `ImageAnalysis.Analyzer`

Reference: `/Users/yarontorbaty/Documents/Code/orbistream/app/src/main/java/com/orbistream/streaming/CameraManager.kt`

### 3. Audio Capture
- Use `AVAudioEngine` or `AudioUnit` for microphone capture
- Feed PCM audio to GStreamer pipeline
- Sample rate: 44100 Hz, Stereo

Reference: `/Users/yarontorbaty/Documents/Code/orbistream/app/src/main/java/com/orbistream/streaming/StreamingService.kt` (audio handling section)

### 4. Settings Management
- Use `UserDefaults` for persistence
- Implement all settings from Android version:

```swift
// Default values (match Android)
struct StreamDefaults {
    static let srtHost = "gcdemo.mcrbox.com"
    static let srtPort = 5000
    static let resolution = "720p"
    static let videoBitrate = 2000  // Kbps
    static let audioBitrate = 128   // Kbps
    static let frameRate = 30
    static let useHardwareEncoder = true
    static let transportMode = "SRT"  // SRT or UDP
    
    // Bondix (stubbed)
    static let bondixEnabled = true
    static let bondixServer = "gcdemo.mcrbox.com"
    static let bondixTunnelPassword = "_'rY8.*Z1!Jh"
    static let bondixTunnelName = "gcdemo"
    static let bondixForSrt = true
    
    // Reconnection
    static let autoReconnect = true
    static let infiniteRetries = false
    static let exponentialBackoff = true
    static let retryDelayMs = 3000
    static let maxReconnectAttempts = 5
}
```

Reference: `/Users/yarontorbaty/Documents/Code/orbistream/app/src/main/java/com/orbistream/data/SettingsRepository.kt`

### 5. Streaming UI
Create a streaming view with:
- Camera preview (full screen)
- Overlay stats panel showing:
  - Stream state (Idle, Connecting, Streaming, Reconnecting, Error)
  - Input FPS / Output FPS
  - Dropped frames (highlight in red if > 5%)
  - Encoder type (Hardware/Software)
  - Video bitrate
  - Audio bitrate
  - Resolution
  - SRT statistics (RTT, bandwidth, packet loss)
- Start/Stop button
- Settings button

Reference: `/Users/yarontorbaty/Documents/Code/orbistream/app/src/main/java/com/orbistream/ui/StreamingActivity.kt`

### 6. Settings UI
Create a settings screen with:
- **SRT Settings**: Host, Port
- **Video Settings**: Resolution picker, Bitrate slider, Frame rate
- **Audio Settings**: Bitrate
- **Encoder Settings**: Hardware encoder toggle
- **Bondix Settings** (stubbed but visible):
  - Enable toggle
  - Server host
  - Tunnel name
  - Tunnel password
  - Route SRT through Bondix toggle
- **Reconnect Settings**:
  - Auto reconnect toggle
  - Infinite retries toggle
  - Exponential backoff toggle
  - Retry delay input
  - Max attempts input
- **Restore Defaults** button

Reference: 
- `/Users/yarontorbaty/Documents/Code/orbistream/app/src/main/res/layout/activity_settings.xml`
- `/Users/yarontorbaty/Documents/Code/orbistream/app/src/main/java/com/orbistream/ui/SettingsActivity.kt`

### 7. Auto-Reconnect Logic
Implement reconnection on SRT connection loss:
- Configurable retry delay (default 3 seconds)
- Optional exponential backoff (2x multiplier, max 30 seconds)
- Configurable max attempts or infinite retries
- Show "Reconnecting" state in UI

Reference: `/Users/yarontorbaty/Documents/Code/orbistream/app/src/main/java/com/orbistream/streaming/StreamingService.kt` (reconnection section)

## Bondix Stub Implementation

Since the Bondix iOS SDK is not yet available, create a stub implementation:

```swift
// BondixManager.swift - STUB

import Foundation

enum BondixStatus: String {
    case disconnected = "Disconnected"
    case connecting = "Connecting"
    case connected = "Connected"
}

struct BondixStats {
    let status: BondixStatus
    let tunnelName: String
    let serverAddress: String
    let channels: [BondixChannel]
    
    static let empty = BondixStats(
        status: .disconnected,
        tunnelName: "",
        serverAddress: "",
        channels: []
    )
}

struct BondixChannel {
    let name: String
    let type: String  // "wifi" or "cellular"
    let isActive: Bool
    let txBytes: Int64
    let rxBytes: Int64
}

class BondixManager {
    static let shared = BondixManager()
    
    private(set) var isInitialized = false
    private(set) var isConnected = false
    private(set) var proxyPort: Int = 28007
    
    var onStatusChange: ((BondixStatus) -> Void)?
    
    func initialize() -> Bool {
        print("[BondixManager] STUB: initialize() called - iOS SDK not available")
        isInitialized = true
        return true
    }
    
    func configure(server: String, tunnelName: String, password: String) -> Bool {
        print("[BondixManager] STUB: configure(server: \(server), tunnel: \(tunnelName)) - iOS SDK not available")
        return true
    }
    
    func connect() -> Bool {
        print("[BondixManager] STUB: connect() called - iOS SDK not available")
        // Simulate connection for UI purposes
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
            self.isConnected = true
            self.onStatusChange?(.connected)
        }
        onStatusChange?(.connecting)
        return true
    }
    
    func disconnect() {
        print("[BondixManager] STUB: disconnect() called")
        isConnected = false
        onStatusChange?(.disconnected)
    }
    
    func getStats() -> BondixStats {
        // Return mock stats for UI display
        if isConnected {
            return BondixStats(
                status: .connected,
                tunnelName: "gcdemo (stub)",
                serverAddress: "gcdemo.mcrbox.com",
                channels: [
                    BondixChannel(name: "WiFi", type: "wifi", isActive: true, txBytes: 0, rxBytes: 0),
                    BondixChannel(name: "Cellular", type: "cellular", isActive: false, txBytes: 0, rxBytes: 0)
                ]
            )
        }
        return .empty
    }
    
    func getProxyAddress() -> String {
        // When real SDK is available, return actual SOCKS5 proxy address
        // For now, return empty to indicate no proxy available
        print("[BondixManager] STUB: getProxyAddress() - returning empty (no proxy)")
        return ""
    }
    
    func shutdown() {
        print("[BondixManager] STUB: shutdown() called")
        isConnected = false
        isInitialized = false
    }
}
```

**Important**: When Bondix is "enabled" but stubbed:
- Show Bondix status in UI as "Connected (Stub)"
- SRT traffic should flow directly (not through proxy) since no real proxy exists
- Log warnings when Bondix features are used

## iOS-Specific Considerations

### Background Execution
- Use `UIBackgroundModes` for audio (`audio`) in Info.plist
- Consider using `beginBackgroundTask` for short streaming continuations
- Note: iOS has stricter background limits than Android's foreground service

### Permissions
Add to Info.plist:
```xml
<key>NSCameraUsageDescription</key>
<string>Camera access is required for live streaming</string>
<key>NSMicrophoneUsageDescription</key>
<string>Microphone access is required for audio streaming</string>
```

### GStreamer iOS Setup
1. Download GStreamer iOS SDK
2. Add framework to Xcode project
3. Create bridging header for C/Objective-C interop
4. Initialize GStreamer in AppDelegate

### Project Structure Suggestion
```
Orbistream/
├── App/
│   ├── OrbitreamApp.swift
│   └── AppDelegate.swift
├── Views/
│   ├── MainView.swift
│   ├── StreamingView.swift
│   └── SettingsView.swift
├── Streaming/
│   ├── StreamingManager.swift      # Equivalent to StreamingService
│   ├── GStreamerBridge.swift       # Swift wrapper for native code
│   ├── CameraCapture.swift
│   └── AudioCapture.swift
├── Data/
│   └── SettingsStore.swift         # UserDefaults wrapper
├── Bondix/
│   └── BondixManager.swift         # STUB
├── Native/
│   ├── GStreamerPipeline.h
│   ├── GStreamerPipeline.m         # Objective-C GStreamer code
│   └── Orbistream-Bridging-Header.h
└── Resources/
    └── Info.plist
```

## UI Design Guidelines

Match the Android app's visual style:
- Dark theme with semi-transparent overlays
- Stats panel: Black background with 70% opacity
- Streaming indicator: Green dot when streaming
- Error states: Red accent color
- Use SF Symbols for icons

## Testing Checklist

- [ ] Camera preview displays correctly
- [ ] Audio capture works
- [ ] SRT connection establishes to test server
- [ ] Stream stats update in real-time
- [ ] Hardware encoder detection works
- [ ] Fallback to software encoder when needed
- [ ] Settings persist across app launches
- [ ] Restore defaults works
- [ ] Auto-reconnect triggers on connection loss
- [ ] Bondix stub shows correct UI state
- [ ] App handles background/foreground transitions

## Build Configuration

- Deployment target: iOS 14.0+
- Swift version: 5.9+
- Architecture: arm64 (no simulator support due to GStreamer)
- Signing: Development team required for device testing

## References

For implementation details, refer to these key Android files:
1. **Native pipeline**: `/Users/yarontorbaty/Documents/Code/orbistream/app/src/main/jni/srt_streamer.cpp`
2. **Streaming service**: `/Users/yarontorbaty/Documents/Code/orbistream/app/src/main/java/com/orbistream/streaming/StreamingService.kt`
3. **Camera handling**: `/Users/yarontorbaty/Documents/Code/orbistream/app/src/main/java/com/orbistream/streaming/CameraManager.kt`
4. **Settings**: `/Users/yarontorbaty/Documents/Code/orbistream/app/src/main/java/com/orbistream/data/SettingsRepository.kt`
5. **Bondix integration**: `/Users/yarontorbaty/Documents/Code/orbistream/app/src/main/java/com/orbistream/bondix/BondixManager.kt`
6. **Bondix integration notes**: `/Users/yarontorbaty/Documents/Code/orbistream/docs/bondix-integration-notes.md`

