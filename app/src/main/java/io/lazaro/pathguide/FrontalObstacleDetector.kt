package io.lazaro.pathguide

import kotlin.math.abs

/**
 * Detecta obstáculos frontales en ROI central, con énfasis en la banda inferior
 * (papeleras, postes de tráfico, bordillos altos, etc.).
 */
class FrontalObstacleDetector {

    private var emaSeverity = 0f
    private var blockedLatch = false

    fun detect(gray: ByteArray, width: Int, height: Int, sensitivity: Float): FrontalObstacleState {
        // ROI un poco más bajo: objetos a altura de cintura/rodilla.
        val roiTop = (height * 0.50f).toInt()
        val roiBottom = (height * 0.94f).toInt()
        val colStart = (width * 0.28f).toInt()
        val colEnd = (width * 0.72f).toInt()

        val centerRef = bandMedian(gray, width, height, roiTop, roiBottom, colStart, colEnd)
        val occupancy = bandOccupancy(gray, width, height, roiTop, roiBottom, colStart, colEnd, centerRef)
        val edgeDensity = bandEdgeDensity(gray, width, height, roiTop, roiBottom, colStart, colEnd)
        val lowerWeight = lowerOccupancy(gray, width, height, roiTop, roiBottom, colStart, colEnd, centerRef)

        // Más peso a la banda inferior (objetos bajos).
        val rawSeverity = (occupancy * 0.28f + edgeDensity * 0.27f + lowerWeight * 0.45f)
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
            closeRange = lowerWeight >= CLOSE_RANGE_LOWER ||
                (emaSeverity >= deactivate && lowerWeight >= 0.22f),
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
                if (value < referenceMedian - 18) occupied++
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
        // Banda inferior más amplia: desde ~35% del ROI hacia abajo.
        val lowerStart = roiTop + ((roiBottom - roiTop) * 0.35f).toInt()
        var occupied = 0
        var total = 0
        for (y in lowerStart until roiBottom) {
            val rowOffset = y * width
            for (x in colStart until colEnd) {
                val idx = rowOffset + x
                if (idx !in gray.indices) continue
                total++
                val value = gray[idx].toInt() and 0xFF
                if (value < referenceMedian - 16) occupied++
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
        /** Más sensible para no perder papeleras/postes a ~2–4 m. */
        private const val ACTIVATE_THRESHOLD = 0.26f
        private const val DEACTIVATE_THRESHOLD = 0.16f
        private const val CLOSE_RANGE_LOWER = 0.24f
    }
}
