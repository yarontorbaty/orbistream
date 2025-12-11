#pragma once

#include <string>
#include <functional>
#include <memory>

namespace orbistream {

/**
 * Transport mode for streaming.
 * 
 * - SRT: Uses SRT protocol with built-in retransmission (use when NOT using Bondix)
 * - UDP: Uses plain UDP MPEG-TS (use with Bondix - Bondix provides reliability)
 */
enum class TransportMode {
    SRT,    // SRT protocol - has its own retransmission
    UDP     // Plain UDP - relies on Bondix for reliability
};

/**
 * Encoder presets (maps to x264 speed-preset).
 */
enum class EncoderPreset {
    ULTRAFAST,  // Fastest, lowest quality
    SUPERFAST,
    VERYFAST,
    FASTER,
    FAST,
    MEDIUM,     // Default balance
    SLOW,
    SLOWER,
    VERYSLOW    // Slowest, highest quality
};

/**
 * Configuration for the streaming pipeline.
 */
struct StreamConfig {
    // Transport mode
    TransportMode transport = TransportMode::UDP;  // Default to UDP for Bondix
    
    // Target host/port (works for both SRT and UDP)
    std::string srtHost;  // TODO: rename to targetHost
    int srtPort = 9000;   // TODO: rename to targetPort
    std::string streamId;
    std::string passphrase;  // Only used for SRT
    
    // Video settings
    int videoWidth = 1920;
    int videoHeight = 1080;
    int videoBitrate = 4000000;  // 4 Mbps
    int frameRate = 30;
    
    // Encoder settings
    EncoderPreset preset = EncoderPreset::ULTRAFAST;  // x264 speed preset
    int keyframeInterval = 2;    // Keyframe every N seconds (GOP size = frameRate * keyframeInterval)
    int bFrames = 0;             // Number of B-frames (0 for low latency)
    
    // Audio settings
    int audioBitrate = 128000;   // 128 kbps
    int sampleRate = 48000;
    int audioChannels = 2;
    
    // Bondix SOCKS5 proxy (for routing through bonded network)
    std::string proxyHost = "127.0.0.1";
    int proxyPort = 28007;
    bool useProxy = true;
};

/**
 * SRT connection state.
 */
enum class SrtConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    BROKEN
};

/**
 * Streaming statistics.
 */
struct StreamStats {
    double currentBitrate = 0.0;     // Current bitrate in bps
    uint64_t bytesSent = 0;          // Total bytes sent
    uint64_t packetsLost = 0;        // Packets lost (SRT stat)
    uint64_t packetsRetransmitted = 0; // Packets retransmitted
    uint64_t packetsDropped = 0;     // Packets dropped
    double rtt = 0.0;                // Round-trip time in ms
    double rttVariance = 0.0;        // RTT variance in ms
    int64_t bandwidth = 0;           // Estimated bandwidth bps
    uint64_t streamTimeMs = 0;       // Stream duration in ms
    SrtConnectionState connectionState = SrtConnectionState::DISCONNECTED;
};

/**
 * Callback types for streaming events.
 */
using StateCallback = std::function<void(bool running, const std::string& message)>;
using StatsCallback = std::function<void(const StreamStats& stats)>;
using ErrorCallback = std::function<void(const std::string& error)>;

/**
 * SrtStreamer handles the GStreamer pipeline for capturing camera/audio
 * and streaming via SRT protocol.
 * 
 * The pipeline is:
 * - Video: Camera -> H.264 encode -> Mux
 * - Audio: Microphone -> AAC encode -> Mux
 * - Mux -> SRT output (via Bondix SOCKS5 proxy)
 */
class SrtStreamer {
public:
    SrtStreamer();
    ~SrtStreamer();

    /**
     * Initialize GStreamer. Must be called once.
     */
    static bool initGStreamer();

    /**
     * Create the streaming pipeline with the given configuration.
     */
    bool createPipeline(const StreamConfig& config);

    /**
     * Start streaming.
     */
    bool start();

    /**
     * Stop streaming.
     */
    void stop();

    /**
     * Check if currently streaming.
     */
    bool isStreaming() const;

    /**
     * Get current streaming statistics.
     */
    StreamStats getStats() const;

    /**
     * Push a video frame from the camera.
     * @param data Raw frame data (NV21 or YUV420)
     * @param size Size of the data
     * @param width Frame width
     * @param height Frame height
     * @param timestampNs Frame timestamp in nanoseconds
     */
    void pushVideoFrame(const uint8_t* data, size_t size, 
                        int width, int height, int64_t timestampNs);

    /**
     * Push audio samples from the microphone.
     * @param data Raw audio samples (PCM S16LE)
     * @param size Size of the data
     * @param sampleRate Sample rate
     * @param channels Number of channels
     * @param timestampNs Timestamp in nanoseconds
     */
    void pushAudioSamples(const uint8_t* data, size_t size,
                          int sampleRate, int channels, int64_t timestampNs);

    /**
     * Set callbacks.
     */
    void setStateCallback(StateCallback callback);
    void setStatsCallback(StatsCallback callback);
    void setErrorCallback(ErrorCallback callback);

private:
    class Impl;
    std::unique_ptr<Impl> pImpl;
};

} // namespace orbistream

