package io.lazaro.pathguide

import kotlin.math.abs
import kotlin.math.max

class FrontalObstacleDetector {

    private var emaSeverity = 0f
    private var blockedLatch = false

    fun detect(gray: ByteArray, width: Int, height: Int, sensitivity: Float): FrontalObstacleState {
        val roiTop = (height * 0.48f).toInt()
        val roiBottom = (height * 0.90f).toInt()
        val colStart = (width * 0.30f).toInt()
        val colEnd = (width * 0.70f).toInt()

        val centerRef = bandMedian(gray, width, height, roiTop, roiBottom, colStart, colEnd)
        val occupancy = bandOccupancy(gray, width, height, roiTop, roiBottom, colStart, colEnd, centerRef)
        val edgeDensity = bandEdgeDensity(gray, width, height, roiTop, roiBottom, colStart, colEnd)
        val lowerWeight = lowerOccupancy(gray, width, height, roiTop, roiBottom, colStart, colEnd, centerRef)

        val rawSeverity = (occupancy * 0.40f + edgeDensity * 0.35f + lowerWeight * 0.25f)
            .coerceIn(0f, 1f)

        emaSeverity = emaSeverity * 0.68f + rawSeverity * 0.32f

        val activate = ACTIVATE_THRESHOLD / sensitivity.coerceIn(0.5f, 2f)
        val deactivate = DEACTIVATE_THRESHOLD / sensitivity.coerceIn(0.5f, 2f)

        blockedLatch = when {
            emaSeverity >= activate -> true
            emaSeverity <= deactivate -> false
            else -> blockedLatch
        }

        return FrontalObstacleState(
            blocked = blockedLatch,
            severity = emaSeverity,
            closeRange = lowerWeight >= 0.45f && emaSeverity >= deactivate,
        )
    }

    fun reset() {
        emaSeverity = 0f
        blockedLatch = false
    }

    private fun bandOccupancy(
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
        var occupied = 0
        var total = 0
        for (y in roiTop until roiBottom) {
            val rowOffset = y * width
            for (x in colStart until colEnd) {
                val idx = rowOffset + x
                if (idx !in gray.indices) continue
                total++
                val value = gray[idx].toInt() and 0xFF
                if (value < referenceMedian - 20) occupied++
            }
        }
        return if (total == 0) 0f else (occupied.toFloat() / total).coerceIn(0f, 1f)
    }

    private fun lowerOccupancy(
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
        val lowerStart = roiTop + ((roiBottom - roiTop) * 0.45f).toInt()
        var occupied = 0
        var total = 0
        for (y in lowerStart until roiBottom) {
            val rowOffset = y * width
            for (x in colStart until colEnd) {
                val idx = rowOffset + x
                if (idx !in gray.indices) continue
                total++
                val value = gray[idx].toInt() and 0xFF
                if (value < referenceMedian - 18) occupied++
            }
        }
        return if (total == 0) 0f else (occupied.toFloat() / total).coerceIn(0f, 1f)
    }

    private fun bandEdgeDensity(
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
                if (idx !in 1 until gray.size - width) continue
                val value = gray[idx].toInt() and 0xFF
                val left = gray[idx - 1].toInt() and 0xFF
                val right = gray[idx + 1].toInt() and 0xFF
                val up = gray[idx - width].toInt() and 0xFF
                val down = gray[idx + width].toInt() and 0xFF
                edgeSum += abs(value - left) + abs(value - right) + abs(value - up) + abs(value - down)
                total++
            }
        }
        return if (total == 0) 0f else (edgeSum.toFloat() / (total * 4f) / 72f).coerceIn(0f, 1f)
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
        private const val ACTIVATE_THRESHOLD = 0.32f
        private const val DEACTIVATE_THRESHOLD = 0.20f
    }
}
