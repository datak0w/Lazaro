package io.lazaro.pathguide

import android.graphics.Bitmap

object GrayBitmapConverter {
    fun toBitmap(
        gray: ByteArray,
        width: Int,
        height: Int,
        maxSide: Int = 720,
    ): Bitmap {
        val scale = if (width > maxSide || height > maxSide) {
            maxSide.toFloat() / maxOf(width, height).toFloat()
        } else {
            1f
        }
        val outW = (width * scale).toInt().coerceAtLeast(1)
        val outH = (height * scale).toInt().coerceAtLeast(1)
        val pixels = IntArray(outW * outH)
        for (y in 0 until outH) {
            val srcY = ((y / scale).toInt()).coerceIn(0, height - 1)
            val srcRow = srcY * width
            val dstRow = y * outW
            for (x in 0 until outW) {
                val srcX = ((x / scale).toInt()).coerceIn(0, width - 1)
                val idx = srcRow + srcX
                val v = if (idx in gray.indices) gray[idx].toInt() and 0xFF else 0
                pixels[dstRow + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        return Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, outW, 0, 0, outW, outH)
        }
    }
}
