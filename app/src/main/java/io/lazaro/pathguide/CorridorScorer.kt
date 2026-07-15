package io.lazaro.pathguide

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class CorridorScorer {

    private var emaLeft = 0f
    private var emaCenter = 0f
    private var emaRight = 0f

    fun score(gray: ByteArray, width: Int, height: Int, sensitivity: Float): CorridorState {
        val roiTop = (height * ROI_TOP_RATIO).toInt()
        val roiBottom = (height * ROI_BOTTOM_RATIO).toInt()
        val roiHeight = max(1, roiBottom - roiTop)

        val leftEnd = (width * LEFT_BAND_RATIO).toInt()
        val centerEnd = (width * (LEFT_BAND_RATIO + CENTER_BAND_RATIO)).toInt()

        val centerRef = bandMedian(gray, width, height, roiTop, roiBottom, leftEnd, centerEnd)
        val rawLeft = bandProximity(gray, width, height, roiTop, roiBottom, 0, leftEnd, centerRef)
        val rawCenter = bandProximity(gray, width, height, roiTop, roiBottom, leftEnd, centerEnd, centerRef)
        val rawRight = bandProximity(gray, width, height, roiTop, roiBottom, centerEnd, width, centerRef)

        emaLeft = smooth(emaLeft, rawLeft)
        emaCenter = smooth(emaCenter, rawCenter)
        emaRight = smooth(emaRight, rawRight)

        val activate = ACTIVATE_THRESHOLD / sensitivity.coerceIn(0.5f, 2f)
        val deactivate = DEACTIVATE_THRESHOLD / sensitivity.coerceIn(0.5f, 2f)

        val left = applyHysteresis(emaLeft, emaLeft > activate, deactivate)
        val center = applyHysteresis(emaCenter, emaCenter > activate, deactivate)
        val right = applyHysteresis(emaRight, emaRight > activate, deactivate)

        return CorridorState(
            leftProximity = left,
            centerProximity = center,
            rightProximity = right,
            isCentered = left < deactivate && right < deactivate,
            isFrontallyBlocked = center > FRONTAL_THRESHOLD / sensitivity.coerceIn(0.5f, 2f),
        )
    }

    fun reset() {
        emaLeft = 0f
        emaCenter = 0f
        emaRight = 0f
    }

    private fun smooth(previous: Float, current: Float): Float {
        return previous * (1f - EMA_ALPHA) + current * EMA_ALPHA
    }

    private fun applyHysteresis(value: Float, active: Boolean, deactivate: Float): Float {
        return if (active || value > deactivate) value else 0f
    }

    private fun bandProximity(
        gray: ByteArray,
        width: Int,
        height: Int,
        roiTop: Int,
        roiBottom: Int,
        colStart: Int,
        colEnd: Int,
        referenceMedian: Int,
    ): Float {
        if (colEnd <= colStart) return 0f

        var edgeSum = 0
        var occupied = 0
        var total = 0
        var lowerWeight = 0f
        var lowerCount = 0f

        for (y in roiTop until roiBottom) {
            val rowOffset = y * width
            val verticalWeight = (y - roiTop).toFloat() / max(1, roiBottom - roiTop)
            for (x in colStart until colEnd) {
                val idx = rowOffset + x
                if (idx !in gray.indices) continue
                val value = gray[idx].toInt() and 0xFF
                total++

                if (x > colStart && x < width - 1) {
                    val left = gray[idx - 1].toInt() and 0xFF
                    val right = gray[idx + 1].toInt() and 0xFF
                    val up = gray[idx - width].toInt() and 0xFF
                    val down = gray[idx + width].toInt() and 0xFF
                    edgeSum += abs(value - left) + abs(value - right) + abs(value - up) + abs(value - down)
                }

                if (value < referenceMedian - 18) {
                    occupied++
                    if (verticalWeight > 0.55f) {
                        lowerWeight += verticalWeight
                        lowerCount++
                    }
                }
            }
        }

        if (total == 0) return 0f

        val edgeScore = (edgeSum.toFloat() / (total * 4f) / 64f).coerceIn(0f, 1f)
        val occupancyScore = (occupied.toFloat() / total).coerceIn(0f, 1f)
        val lowerScore = if (lowerCount > 0f) (lowerWeight / lowerCount).coerceIn(0f, 1f) else 0f

        return (edgeScore * 0.45f + occupancyScore * 0.35f + lowerScore * 0.20f).coerceIn(0f, 1f)
    }

    private fun bandMedian(
        gray: ByteArray,
        width: Int,
        height: Int,
        roiTop: Int,
        roiBottom: Int,
        colStart: Int,
        colEnd: Int,
    ): Int {
        val histogram = IntArray(256)
        var count = 0
        for (y in roiTop until roiBottom) {
            val rowOffset = y * width
            for (x in colStart until colEnd) {
                val idx = rowOffset + x
                if (idx !in gray.indices) continue
                histogram[gray[idx].toInt() and 0xFF]++
                count++
            }
        }
        if (count == 0) return 128
        val target = count / 2
        var cumulative = 0
        for (i in histogram.indices) {
            cumulative += histogram[i]
            if (cumulative >= target) return i
        }
        return 128
    }

    companion object {
        private const val ROI_TOP_RATIO = PathGuideRoi.CORRIDOR_TOP
        private const val ROI_BOTTOM_RATIO = PathGuideRoi.CORRIDOR_BOTTOM
        private const val LEFT_BAND_RATIO = PathGuideRoi.LEFT_BAND
        private const val CENTER_BAND_RATIO = PathGuideRoi.CENTER_BAND
        private const val EMA_ALPHA = 0.20f
        private const val ACTIVATE_THRESHOLD = 0.30f
        private const val DEACTIVATE_THRESHOLD = 0.18f
        private const val FRONTAL_THRESHOLD = 0.35f
    }
}
