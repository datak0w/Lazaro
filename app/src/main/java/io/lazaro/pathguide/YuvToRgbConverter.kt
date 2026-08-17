package io.lazaro.pathguide

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

/**
 * Convierte un frame YUV_420_888 a Bitmap RGB (rotado según orientation del ImageProxy).
 */
object YuvToRgbConverter {

    fun imageProxyToBitmap(image: ImageProxy, maxSide: Int = 1024): Bitmap? {
        if (image.format != ImageFormat.YUV_420_888) return null
        val nv21 = yuv420888ToNv21(image) ?: return null
        val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        if (!yuv.compressToJpeg(Rect(0, 0, image.width, image.height), 85, out)) {
            return null
        }
        val bytes = out.toByteArray()
        val decoded = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: return null
        val rotated = rotateIfNeeded(decoded, image.imageInfo.rotationDegrees)
        if (rotated !== decoded) {
            decoded.recycle()
        }
        return scaleDown(rotated, maxSide)
    }

    private fun rotateIfNeeded(bitmap: Bitmap, degrees: Int): Bitmap {
        val normalized = ((degrees % 360) + 360) % 360
        if (normalized == 0) return bitmap
        val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun scaleDown(bitmap: Bitmap, maxSide: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxSide && h <= maxSide) return bitmap
        val scale = maxSide.toFloat() / maxOf(w, h).toFloat()
        val outW = (w * scale).toInt().coerceAtLeast(1)
        val outH = (h * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, outW, outH, true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun yuv420888ToNv21(image: ImageProxy): ByteArray? {
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return null
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        val nv21 = ByteArray(width * height + (width * height) / 2)
        var pos = 0
        for (row in 0 until height) {
            val yRow = row * yRowStride
            for (col in 0 until width) {
                nv21[pos++] = yBuffer.get(yRow + col * yPixelStride)
            }
        }

        val chromaHeight = height / 2
        val chromaWidth = width / 2
        for (row in 0 until chromaHeight) {
            val uvRow = row * uvRowStride
            for (col in 0 until chromaWidth) {
                val uvIndex = uvRow + col * uvPixelStride
                // NV21 = YYYY… VU VU…
                nv21[pos++] = vBuffer.get(uvIndex)
                nv21[pos++] = uBuffer.get(uvIndex)
            }
        }
        return nv21
    }
}
