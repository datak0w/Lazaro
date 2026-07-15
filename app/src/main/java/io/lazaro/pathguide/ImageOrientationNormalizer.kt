package io.lazaro.pathguide

import androidx.camera.core.ImageProxy

internal object ImageOrientationNormalizer {

    fun toUprightGray(image: ImageProxy): GrayFrame? {
        val raw = image.toRawGrayPlane() ?: return null
        return rotate(raw, image.imageInfo.rotationDegrees)
    }

    private fun rotate(frame: GrayFrame, degrees: Int): GrayFrame {
        val normalized = ((degrees % 360) + 360) % 360
        return when (normalized) {
            0 -> frame
            90 -> rotate90Clockwise(frame)
            180 -> rotate180(frame)
            270 -> rotate90CounterClockwise(frame)
            else -> frame
        }
    }

    private fun rotate90Clockwise(frame: GrayFrame): GrayFrame {
        val srcW = frame.width
        val srcH = frame.height
        val dstW = srcH
        val dstH = srcW
        val out = ByteArray(dstW * dstH)
        for (y in 0 until srcH) {
            for (x in 0 until srcW) {
                val dstX = srcH - 1 - y
                val dstY = x
                out[dstY * dstW + dstX] = frame.bytes[y * srcW + x]
            }
        }
        return GrayFrame(out, dstW, dstH)
    }

    private fun rotate90CounterClockwise(frame: GrayFrame): GrayFrame {
        val srcW = frame.width
        val srcH = frame.height
        val dstW = srcH
        val dstH = srcW
        val out = ByteArray(dstW * dstH)
        for (y in 0 until srcH) {
            for (x in 0 until srcW) {
                val dstX = y
                val dstY = srcW - 1 - x
                out[dstY * dstW + dstX] = frame.bytes[y * srcW + x]
            }
        }
        return GrayFrame(out, dstW, dstH)
    }

    private fun rotate180(frame: GrayFrame): GrayFrame {
        val out = ByteArray(frame.bytes.size)
        val size = frame.bytes.size
        for (i in frame.bytes.indices) {
            out[size - 1 - i] = frame.bytes[i]
        }
        return GrayFrame(out, frame.width, frame.height)
    }
}

private fun ImageProxy.toRawGrayPlane(): GrayFrame? {
    val mediaImage = image ?: return null
    val plane = mediaImage.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    val frameWidth = width
    val frameHeight = height
    if (frameWidth <= 0 || frameHeight <= 0) return null

    val gray = ByteArray(frameWidth * frameHeight)
    for (y in 0 until frameHeight) {
        val rowBase = y * rowStride
        val destBase = y * frameWidth
        for (x in 0 until frameWidth) {
            gray[destBase + x] = buffer.get(rowBase + x)
        }
    }
    return GrayFrame(gray, frameWidth, frameHeight)
}
