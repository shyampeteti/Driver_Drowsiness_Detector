package com.example.drowsiness.camera

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy

/** Direct YUV_420_888 to RGB conversion, avoiding NV21, JPEG, and JPEG decode intermediates. */
object YuvBitmapConverter {
    private var scratchPixels = IntArray(0)
    private var scratchWidth = 0
    private var scratchHeight = 0

    @Synchronized
    fun convert(image: ImageProxy): Bitmap? {
        val yPlane = image.planes.getOrNull(0) ?: return null
        val uPlane = image.planes.getOrNull(1) ?: return null
        val vPlane = image.planes.getOrNull(2) ?: return null
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return null
        val pixelCount = width * height
        if (scratchWidth != width || scratchHeight != height || scratchPixels.size != pixelCount) {
            scratchPixels = IntArray(pixelCount)
            scratchWidth = width
            scratchHeight = height
        }

        val yBuffer = yPlane.buffer.duplicate()
        val uBuffer = uPlane.buffer.duplicate()
        val vBuffer = vPlane.buffer.duplicate()
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        for (row in 0 until height) {
            val chromaRow = (row / 2).coerceAtMost(chromaHeight - 1)
            for (col in 0 until width) {
                val yIndex = row * yPlane.rowStride + col * yPlane.pixelStride
                val chromaCol = (col / 2).coerceAtMost(chromaWidth - 1)
                val uIndex = chromaRow * uPlane.rowStride + chromaCol * uPlane.pixelStride
                val vIndex = chromaRow * vPlane.rowStride + chromaCol * vPlane.pixelStride
                if (yIndex !in 0 until yBuffer.limit() || uIndex !in 0 until uBuffer.limit() || vIndex !in 0 until vBuffer.limit()) return null

                val y = (yBuffer.get(yIndex).toInt() and 0xff) - 16
                val u = (uBuffer.get(uIndex).toInt() and 0xff) - 128
                val v = (vBuffer.get(vIndex).toInt() and 0xff) - 128
                val c = y.coerceAtLeast(0)
                val red = ((298 * c + 409 * v + 128) shr 8).coerceIn(0, 255)
                val green = ((298 * c - 100 * u - 208 * v + 128) shr 8).coerceIn(0, 255)
                val blue = ((298 * c + 516 * u + 128) shr 8).coerceIn(0, 255)
                scratchPixels[row * width + col] = (0xff shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }

        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(scratchPixels, 0, width, 0, 0, width, height)
        }
    }
}
