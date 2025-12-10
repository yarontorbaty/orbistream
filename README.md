# OrbiStream

**Stream anywhere with network bonding**

OrbiStream is an Android application that captures video and audio from your device's camera and microphone, and streams it to an SRT (Secure Reliable Transport) endpoint. It leverages **Bondix** for network bonding, combining WiFi and cellular connections for reliable streaming even in challenging network conditions.

## Features

- 📹 **Live Video Streaming** - Capture and stream video via SRT protocol
- 🎤 **Audio Capture** - Stereo audio recording at 48kHz
- 🌐 **Network Bonding** - Combines WiFi + Cellular via Bondix for reliable connectivity
- 📊 **Real-time Stats** - Monitor bitrate, stream duration, and network status
- ⚙️ **Configurable Quality** - Adjust resolution, bitrate, and frame rate
- 🔒 **SRT Encryption** - Optional passphrase protection for streams

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        OrbiStream App                            │
├─────────────────────────────────────────────────────────────────┤
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────────────┐  │
│  │  CameraX    │    │ AudioRecord │    │   Bondix Manager    │  │
│  │  (Video)    │    │  (Audio)    │    │  (Network Bonding)  │  │
│  └──────┬──────┘    └──────┬──────┘    └──────────┬──────────┘  │
│         │                  │                       │             │
│         ▼                  ▼                       ▼             │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │              GStreamer Pipeline (Native C++)                 ││
│  │  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────────────┐ ││
│  │  │ appsrc  │  │ x264enc │  │ voaacenc│  │    srtsink      │ ││
│  │  │ (video) │──│  H.264  │──│   AAC   │──│ (via SOCKS5)    │ ││
│  │  └─────────┘  └─────────┘  └─────────┘  └────────┬────────┘ ││
│  └──────────────────────────────────────────────────┼──────────┘│
│                                                      │           │
└──────────────────────────────────────────────────────┼───────────┘
                                                       │
                     ┌─────────────────────────────────┼───────────┐
                     │         Bondix Engine           │           │
                     │  ┌───────────────────────────┐  │           │
                     │  │     SOCKS5 Proxy          │◀─┘           │
                     │  │    (127.0.0.1:28007)      │              │
                     │  └───────────┬───────────────┘              │
                     │              │                              │
                     │  ┌───────────┴───────────┐                  │
                     │  │                       │                  │
                     │  ▼                       ▼                  │
                     │ WiFi                 Cellular               │
                     │ Interface            Interface              │
                     └─────────────────────────────────────────────┘
                                       │
                                       ▼
                              ┌─────────────────┐
                              │   SRT Server    │
                              │  (Destination)  │
                              └─────────────────┘
```

## Requirements

- Android 8.0 (API 26) or higher
- Camera permission
- Microphone permission
- Network access

### Optional: Bondix AAR

For network bonding functionality, you need to add the Bondix library:

1. Download `bondix-root-release.aar` from https://releases.bondix.dev/android/
2. Place it in `app/libs/`
3. Rebuild the project

Without the Bondix AAR, the app will use standard single-network streaming.

### Optional: GStreamer Android SDK

For the native streaming pipeline:

1. Download GStreamer Android SDK from https://gstreamer.freedesktop.org/data/pkg/android/
2. Extract to a directory
3. Set `GSTREAMER_ROOT_ANDROID` environment variable to that path
4. Rebuild the project

Without GStreamer, the app builds with a stub implementation.

## Building

```bash
# Clone the repository
git clone https://github.com/yourusername/orbistream.git
cd orbistream

# Build debug APK
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug
```

## Configuration

### SRT Settings

- **Host**: SRT server hostname or IP address
- **Port**: SRT port (default: 9000)
- **Stream ID**: Optional SRT stream identifier
- **Passphrase**: Optional encryption passphrase

### Bondix Settings

- **Tunnel Name**: Your Bondix tunnel identifier
- **Tunnel Password**: Bondix tunnel password
- **Endpoint Server**: Bondix server endpoint

### Video Settings

| Resolution | Recommended Bitrate |
|------------|---------------------|
| 480p       | 1-2 Mbps            |
| 720p       | 2-4 Mbps            |
| 1080p      | 4-8 Mbps            |
| 1440p      | 8-15 Mbps           |
| 4K         | 15-30 Mbps          |

### Audio Settings

- **Bitrate**: 64-320 kbps (recommended: 128 kbps)
- **Sample Rate**: 44100 or 48000 Hz

## Project Structure

```
orbistream/
├── app/
│   ├── src/main/
│   │   ├── java/
│   │   │   ├── com/orbistream/
│   │   │   │   ├── OrbiStreamApp.kt        # Application class
│   │   │   │   ├── ui/
│   │   │   │   │   ├── MainActivity.kt     # Main screen
│   │   │   │   │   ├── StreamingActivity.kt # Live streaming
│   │   │   │   │   └── SettingsActivity.kt # Configuration
│   │   │   │   ├── streaming/
│   │   │   │   │   ├── NativeStreamer.kt   # JNI wrapper
│   │   │   │   │   ├── StreamingService.kt # Foreground service
│   │   │   │   │   ├── CameraManager.kt    # CameraX capture
│   │   │   │   │   └── AudioCapture.kt     # Audio recording
│   │   │   │   ├── bondix/
│   │   │   │   │   ├── BondixManager.kt    # Bondix lifecycle
│   │   │   │   │   ├── NetworkRegistry.kt  # Network tracking
│   │   │   │   │   └── SocketBinder.kt     # FD binding
│   │   │   │   └── data/
│   │   │   │       └── SettingsRepository.kt
│   │   │   └── bondix/pkg/                 # Bondix stub
│   │   ├── cpp/
│   │   │   ├── CMakeLists.txt
│   │   │   ├── orbistream_jni.cpp          # JNI bindings
│   │   │   ├── srt_streamer.cpp            # GStreamer pipeline
│   │   │   └── srt_streamer.h
│   │   └── res/
│   │       ├── layout/
│   │       ├── values/
│   │       └── drawable/
│   └── libs/                               # Place Bondix AAR here
├── docs/
│   └── integration/
│       └── README.md                       # Bondix integration guide
└── README.md
```

## Bondix Integration

OrbiStream integrates with Bondix following the libbondix Android documentation:

1. **NetworkRegistry**: Tracks available networks (WiFi, Cellular) using Android's ConnectivityManager
2. **SocketBinder**: Binds socket file descriptors to specific networks via JNI callback
3. **BondixManager**: Manages Bondix lifecycle and configuration

The integration enables:
- **Network Bonding**: Combines multiple networks for increased bandwidth
- **Seamless Failover**: Automatically switches when a network fails
- **SOCKS5 Proxy**: Routes SRT traffic through the bonded connection

## License

MIT License - see LICENSE file for details.

## Acknowledgments

- [Bondix](https://bondix.dev) - Network bonding technology
- [GStreamer](https://gstreamer.freedesktop.org) - Multimedia framework
- [CameraX](https://developer.android.com/training/camerax) - Android camera library

