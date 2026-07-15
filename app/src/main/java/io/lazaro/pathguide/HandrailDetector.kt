package io.lazaro.pathguide

import kotlin.math.abs
import kotlin.math.max

class HandrailDetector {

    fun detect(gray: ByteArray, width: Int, height: Int): HandrailSide {
        val roiTop = (height * 0.35f).toInt()
        val roiBottom = (height * 0.88f).toInt()

        val leftEnd = (width * 0.18f).toInt()
        val rightStart = (width * 0.82f).toInt()

        val leftScore = verticalEdgeScore(gray, width, height, roiTop, roiBottom, 0, leftEnd)
        val rightScore = verticalEdgeScore(gray, width, height, roiTop, roiBottom, rightStart, width)

        return when {
            leftScore >= HANDRAIL_THRESHOLD && leftScore - rightScore >= 0.15f -> HandrailSide.LEFT
            rightScore >= HANDRAIL_THRESHOLD && rightScore - leftScore >= 0.15f -> HandrailSide.RIGHT
            leftScore < HANDRAIL_THRESHOLD * 0.85f && rightScore < HANDRAIL_THRESHOLD * 0.85f -> HandrailSide.NONE
            leftScore > rightScore && leftScore >= HANDRAIL_THRESHOLD * 0.75f -> HandrailSide.LEFT
            rightScore > leftScore && rightScore >= HANDRAIL_THRESHOLD * 0.75f -> HandrailSide.RIGHT
            else -> HandrailSide.UNKNOWN
        }
    }

    private fun verticalEdgeScore(
        gray: ByteArray,
        width: Int,
        height: Int,
        roiTop: Int,
        roiBottom: Int,
        colStart: Int,
        colEnd: Int,
    ): Float {
        if (colEnd <= colStart) return 0f

        var edgeSum = 0
        var total = 0
        for (y in roiTop until roiBottom) {
            val rowOffset = y * width
            for (x in (colStart + 1) until (colEnd - 1)) {
                val idx = rowOffset + x
                if (idx !in 1 until gray.size - 1) continue
                val value = gray[idx].toInt() and 0xFF
                val left = gray[idx - 1].toInt() and 0xFF
                val right = gray[idx + 1].toInt() and 0xFF
                edgeSum += abs(value - left) + abs(value - right)
                total++
            }
        }
        if (total == 0) return 0f
        return (edgeSum.toFloat() / (total * 2f) / 48f).coerceIn(0f, 1f)
    }

    companion object {
        private const val HANDRAIL_THRESHOLD = 0.34f
    }
}
