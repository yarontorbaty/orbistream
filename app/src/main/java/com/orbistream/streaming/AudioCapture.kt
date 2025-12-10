package com.orbistream.streaming

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AudioCapture handles microphone audio capture for streaming.
 * 
 * Captures PCM audio data and passes it to a callback for encoding.
 */
class AudioCapture(private val context: Context) {
    
    companion object {
        private const val TAG = "AudioCapture"
        
        // Audio configuration
        const val SAMPLE_RATE = 48000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val CHANNELS = 2
    }

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private var isCapturing = false
    
    private var audioCallback: ((ByteArray, Int, Int, Long) -> Unit)? = null
    private var sampleRate = SAMPLE_RATE
    private var channels = CHANNELS

    /**
     * Set the callback for audio data.
     * 
     * @param callback Called with (audioData, sampleRate, channels, timestampNs)
     */
    fun setAudioCallback(callback: (ByteArray, Int, Int, Long) -> Unit) {
        audioCallback = callback
    }

    /**
     * Configure audio settings.
     */
    fun configure(sampleRate: Int = SAMPLE_RATE, channels: Int = CHANNELS) {
        this.sampleRate = sampleRate
        this.channels = channels
    }

    /**
     * Start audio capture.
     * 
     * @return true if capture started successfully
     */
    fun start(): Boolean {
        if (isCapturing) {
            Log.w(TAG, "Already capturing")
            return true
        }

        // Check permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) 
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Audio permission not granted")
            return false
        }

        val channelConfig = if (channels == 1) {
            AudioFormat.CHANNEL_IN_MONO
        } else {
            AudioFormat.CHANNEL_IN_STEREO
        }

        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, AUDIO_FORMAT)
        if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
            Log.e(TAG, "Invalid buffer size: $bufferSize")
            return false
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                AUDIO_FORMAT,
                bufferSize * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized")
                audioRecord?.release()
                audioRecord = null
                return false
            }

            audioRecord?.startRecording()
            isCapturing = true

            // Start capture loop
            captureJob = CoroutineScope(Dispatchers.IO).launch {
                captureLoop(bufferSize)
            }

            Log.i(TAG, "Audio capture started: ${sampleRate}Hz, $channels channels")
            return true
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception: ${e.message}")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start capture: ${e.message}")
            return false
        }
    }

    private suspend fun captureLoop(bufferSize: Int) {
        val buffer = ByteArray(bufferSize)
        val startTime = System.nanoTime()

        while (isCapturing && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
            
            if (bytesRead > 0) {
                val timestampNs = System.nanoTime() - startTime
                
                // Copy only the bytes that were read
                val audioData = if (bytesRead == buffer.size) {
                    buffer
                } else {
                    buffer.copyOf(bytesRead)
                }
                
                audioCallback?.invoke(audioData, sampleRate, channels, timestampNs)
            } else if (bytesRead < 0) {
                Log.e(TAG, "Audio read error: $bytesRead")
                break
            }
        }
    }

    /**
     * Stop audio capture.
     */
    fun stop() {
        isCapturing = false
        captureJob?.cancel()
        captureJob = null

        audioRecord?.let { record ->
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
                record.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping audio record: ${e.message}")
            }
        }
        audioRecord = null

        Log.i(TAG, "Audio capture stopped")
    }

    /**
     * Check if currently capturing.
     */
    fun isCapturing(): Boolean = isCapturing

    /**
     * Get current sample rate.
     */
    fun getSampleRate(): Int = sampleRate

    /**
     * Get number of channels.
     */
    fun getChannels(): Int = channels
}

