package com.example.drowsiness.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

/** Simple compatibility-first YUV_420_888 -> Bitmap converter for prototype frame rates. */
object YuvBitmapConverter {
    fun convert(image: ImageProxy): Bitmap? {
        val y = image.planes.getOrNull(0) ?: return null
        val u = image.planes.getOrNull(1) ?: return null
        val v = image.planes.getOrNull(2) ?: return null
        val width = image.width
        val height = image.height
        val nv21 = ByteArray(width * height + width * height / 2)
        copyPlane(y, width, height, nv21, 0, 1)
        // NV21 chroma order is V then U.
        var offset = width * height
        val rowStride = v.rowStride
        val pixelStride = v.pixelStride
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                val vi = row * rowStride + col * pixelStride
                val ui = row * u.rowStride + col * u.pixelStride
                if (offset + 1 < nv21.size && vi < v.buffer.limit() && ui < u.buffer.limit()) {
                    nv21[offset++] = v.buffer.get(vi)
                    nv21[offset++] = u.buffer.get(ui)
                }
            }
        }
        val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        if (!yuv.compressToJpeg(Rect(0, 0, width, height), 85, out)) return null
        return BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
    }

    private fun copyPlane(plane: ImageProxy.PlaneProxy, width: Int, height: Int, out: ByteArray, outOffset: Int, outPixelStride: Int) {
        val buffer = plane.buffer
        var offset = outOffset
        for (row in 0 until height) {
            for (col in 0 until width) {
                val index = row * plane.rowStride + col * plane.pixelStride
                if (index < buffer.limit() && offset < out.size) out[offset] = buffer.get(index)
                offset += outPixelStride
            }
        }
    }
}
