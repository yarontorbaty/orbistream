package com.orbistream.streaming

import android.content.Context
import android.graphics.ImageFormat
import android.media.Image
import android.util.Log
import android.util.Size
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * CameraManager handles camera initialization and frame capture using CameraX.
 * 
 * Captured frames are passed to a callback for encoding and streaming.
 */
class CameraManager(private val context: Context) {
    
    companion object {
        private const val TAG = "CameraManager"
        
        // Common streaming resolutions
        val RESOLUTION_1080P = Size(1920, 1080)
        val RESOLUTION_720P = Size(1280, 720)
        val RESOLUTION_480P = Size(854, 480)
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var preview: Preview? = null
    private var camera: Camera? = null
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    
    private var frameCallback: ((ByteArray, Int, Int, Long) -> Unit)? = null
    private var targetResolution: Size = RESOLUTION_1080P
    private var useFrontCamera = false

    /**
     * Set the callback for frame data.
     * 
     * @param callback Called with (frameData, width, height, timestampNs)
     */
    fun setFrameCallback(callback: (ByteArray, Int, Int, Long) -> Unit) {
        frameCallback = callback
    }

    /**
     * Set target resolution for capture.
     */
    fun setTargetResolution(resolution: Size) {
        targetResolution = resolution
    }

    /**
     * Set whether to use front or back camera.
     */
    fun setUseFrontCamera(useFront: Boolean) {
        useFrontCamera = useFront
    }

    /**
     * Start camera preview and capture.
     * 
     * @param lifecycleOwner Activity or Fragment lifecycle
     * @param previewView The view for camera preview
     */
    fun start(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases(lifecycleOwner, previewView)
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCameraUseCases(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val provider = cameraProvider ?: return
        
        // Unbind any existing use cases
        provider.unbindAll()
        
        // Camera selector
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(
                if (useFrontCamera) CameraSelector.LENS_FACING_FRONT
                else CameraSelector.LENS_FACING_BACK
            )
            .build()
        
        // Preview use case
        preview = Preview.Builder()
            .setTargetResolution(targetResolution)
            .build()
            .also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
        
        // Image analysis for frame capture
        imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(targetResolution)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    processFrame(imageProxy)
                }
            }
        
        try {
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalysis
            )
            
            Log.i(TAG, "Camera bound successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Camera binding failed: ${e.message}")
        }
    }

    private fun processFrame(imageProxy: ImageProxy) {
        val callback = frameCallback
        if (callback == null) {
            imageProxy.close()
            return
        }

        try {
            // Convert YUV_420_888 to NV21 for native processing
            val nv21Data = yuv420888ToNv21(imageProxy)
            
            callback(
                nv21Data,
                imageProxy.width,
                imageProxy.height,
                imageProxy.imageInfo.timestamp
            )
        } catch (e: Exception) {
            Log.e(TAG, "Frame processing error: ${e.message}")
        } finally {
            imageProxy.close()
        }
    }

    /**
     * Convert YUV_420_888 to NV21 format.
     */
    private fun yuv420888ToNv21(imageProxy: ImageProxy): ByteArray {
        val image = imageProxy.image ?: throw IllegalStateException("Image is null")
        
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer
        
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        
        // NV21 format: Y plane followed by interleaved VU
        val nv21 = ByteArray(ySize + uSize + vSize)
        
        // Copy Y plane
        yBuffer.get(nv21, 0, ySize)
        
        // Get UV pixel stride
        val uvPixelStride = image.planes[1].pixelStride
        val uvRowStride = image.planes[1].rowStride
        
        if (uvPixelStride == 2) {
            // Already interleaved, just need to copy VU in correct order
            val uvBuffer = if (image.planes[2].buffer.remaining() > image.planes[1].buffer.remaining()) {
                image.planes[2].buffer // V plane has more data, use it
            } else {
                image.planes[1].buffer
            }
            uvBuffer.rewind()
            uvBuffer.get(nv21, ySize, uvBuffer.remaining().coerceAtMost(nv21.size - ySize))
        } else {
            // Need to interleave U and V
            var offset = ySize
            for (row in 0 until image.height / 2) {
                for (col in 0 until image.width / 2) {
                    val uIndex = row * uvRowStride + col * uvPixelStride
                    val vIndex = row * uvRowStride + col * uvPixelStride
                    
                    if (offset + 1 < nv21.size && vIndex < vSize && uIndex < uSize) {
                        nv21[offset++] = vBuffer.get(vIndex)
                        nv21[offset++] = uBuffer.get(uIndex)
                    }
                }
            }
        }
        
        return nv21
    }

    /**
     * Stop camera capture.
     */
    fun stop() {
        cameraProvider?.unbindAll()
        camera = null
    }

    /**
     * Release resources.
     */
    fun release() {
        stop()
        cameraExecutor.shutdown()
    }

    /**
     * Toggle flash/torch.
     */
    fun toggleTorch(): Boolean {
        val cam = camera ?: return false
        val newState = !(cam.cameraInfo.torchState.value == TorchState.ON)
        cam.cameraControl.enableTorch(newState)
        return newState
    }

    /**
     * Switch between front and back camera.
     */
    fun switchCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        useFrontCamera = !useFrontCamera
        start(lifecycleOwner, previewView)
    }

    /**
     * Get current camera resolution.
     */
    fun getCurrentResolution(): Size? {
        return imageAnalysis?.resolutionInfo?.resolution
    }
}

