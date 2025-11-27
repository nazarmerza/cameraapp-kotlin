package com.nmerza.cameraapp

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ExecutorService

class ImageProcessor(
    private val nativeFilter: NativeFilter,
    private val cameraExecutor: ExecutorService
) : ImageAnalysis.Analyzer {

    private val _processedBitmap: MutableStateFlow<Bitmap?> = MutableStateFlow(null)
    val processedBitmap: StateFlow<Bitmap?> = _processedBitmap

    // Public property to hold the current lens facing direction
    var lensFacing: Int = CameraSelector.LENS_FACING_BACK

    fun setActiveFilter(filterName: String) {
        cameraExecutor.execute {
            nativeFilter.setActiveFilter(filterName)
        }
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val image = imageProxy.image
        if (image == null || image.format != ImageFormat.YUV_420_888) {
            imageProxy.close()
            return
        }

        val planes = image.planes

        val processedRgba = nativeFilter.processFrame(
            planes[0].buffer,       // Y
            planes[1].buffer,       // U
            planes[2].buffer,       // V
            image.width,
            image.height,
            planes[0].rowStride,    // Y Stride
            planes[1].rowStride,    // UV Stride
            planes[1].pixelStride   // UV Pixel Stride
        )

        // This is the bitmap with the filter applied, but without rotation
        val rawBitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        rawBitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(processedRgba))

        // Get rotation degrees from the ImageProxy
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees.toFloat()

        val matrix = Matrix().apply {
            postRotate(rotationDegrees)

            // Mirror the image for the front camera
            if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                postScale(-1f, 1f, image.width / 2f, image.height / 2f)
            }
        }

        // Create the final, correctly rotated bitmap
        val rotatedBitmap = Bitmap.createBitmap(
            rawBitmap, 0, 0, image.width, image.height, matrix, true
        )

        // Update the flow for the Compose UI to redraw.
        _processedBitmap.value = rotatedBitmap

        imageProxy.close()
    }
}