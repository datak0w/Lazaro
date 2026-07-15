package io.lazaro.pathguide

import android.graphics.Bitmap

object GrayBitmapConverter {
    fun toBitmap(gray: ByteArray, width: Int, height: Int): Bitmap {
        val pixels = IntArray(width * height)
        val limit = minOf(gray.size, pixels.size)
        for (i in 0 until limit) {
            val v = gray[i].toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }
}
