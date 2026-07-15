package io.lazaro.pathguide

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

class StairDetector {

    private var stableMs = 0L
    private var lastFrameMs = 0L
    private var emaConfidence = 0f
    private var lastPeakCount = 0

    fun detect(gray: ByteArray, width: Int, height: Int, nowMs: Long): StairState {
        if (lastFrameMs == 0L) lastFrameMs = nowMs
        val delta = nowMs - lastFrameMs
        lastFrameMs = nowMs

        val roiTop = (height * PathGuideRoi.STAIR_TOP).toInt()
        val roiBottom = (height * PathGuideRoi.STAIR_BOTTOM).toInt()
        val colStart = (width * PathGuideRoi.STAIR_COL_START).toInt()
        val colEnd = (width * PathGuideRoi.STAIR_COL_END).toInt()

        val analysis = analyzePeaks(gray, width, height, roiTop, roiBottom, colStart, colEnd)
        lastPeakCount = analysis.peakCount

        val rawConfidence = scoreConfidence(analysis)

        emaConfidence = emaConfidence * 0.65f + rawConfidence * 0.35f

        if (emaConfidence >= 0.50f) {
            stableMs += delta
        } else {
            stableMs = 0L
        }

        return StairState(
            detected = stableMs >= STABLE_MS && analysis.peakCount >= MIN_STEP_PEAKS,
            confidence = emaConfidence,
            horizontalPeaks = analysis.peakCount,
            regularSpacing = analysis.regularSpacing,
            spacingConsistency = analysis.spacingConsistency,
        )
    }

    fun reset() {
        stableMs = 0L
        lastFrameMs = 0L
        emaConfidence = 0f
        lastPeakCount = 0
    }

    fun lastPeakCount(): Int = lastPeakCount

    private data class PeakAnalysis(
        val peakCount: Int,
        val regularSpacing: Boolean,
        val moderateSpacing: Boolean,
        val spacingConsistency: Float,
        val lowerHalfRatio: Float,
        val strongPeakRatio: Float,
    )

    private fun scoreConfidence(analysis: PeakAnalysis): Float {
        val peaks = analysis.peakCount
        if (peaks < MIN_STEP_PEAKS) return 0f

        val spacingOk = analysis.regularSpacing || analysis.moderateSpacing
        val lowerOk = analysis.lowerHalfRatio >= 0.38f
        val strongOk = analysis.strongPeakRatio >= 0.45f

        return when {
            peaks in MIN_STEP_PEAKS..MAX_STEP_PEAKS && spacingOk && lowerOk && strongOk -> 0.92f
            peaks in MIN_STEP_PEAKS..MAX_STEP_PEAKS && spacingOk && lowerOk -> 0.78f
            peaks in MIN_STEP_PEAKS..MAX_STEP_PEAKS && analysis.spacingConsistency >= 0.50f && lowerOk -> 0.68f
            peaks >= MIN_STEP_PEAKS && analysis.spacingConsistency >= 0.42f && analysis.lowerHalfRatio >= 0.32f -> 0.52f
            else -> 0f
        }
    }

    private fun analyzePeaks(
        gray: ByteArray,
        width: Int,
        height: Int,
        roiTop: Int,
        roiBottom: Int,
        colStart: Int,
        colEnd: Int,
    ): PeakAnalysis {
        val rowScores = FloatArray(max(1, roiBottom - roiTop))
        var rowIndex = 0
        for (y in roiTop until roiBottom) {
            var edge = 0
            val rowOffset = y * width
            for (x in colStart until colEnd) {
                val idx = rowOffset + x
                if (idx <= 0 || idx + width >= gray.size) continue
                val value = gray[idx].toInt() and 0xFF
                val up = gray[idx - width].toInt() and 0xFF
                val down = gray[idx + width].toInt() and 0xFF
                edge += abs(value - up) + abs(value - down)
            }
            rowScores[rowIndex++] = edge.toFloat() / max(1, colEnd - colStart)
        }

        if (rowScores.isEmpty()) {
            return PeakAnalysis(0, false, false, 0f, 0f, 0f)
        }

        val smoothed = smoothRowScores(rowScores, SMOOTH_RADIUS)
        val mean = smoothed.average().toFloat()
        val p75 = percentile(smoothed, 0.75f)
        val threshold = max(mean * 1.35f, p75 * 0.82f)

        val peakRows = findPeaks(smoothed, threshold, MIN_PEAK_GAP_ROWS)
        val spacing = spacingMetrics(peakRows)

        val lowerHalfStart = smoothed.size / 2
        val lowerPeaks = peakRows.count { it >= lowerHalfStart }
        val lowerHalfRatio = if (peakRows.isEmpty()) 0f else lowerPeaks.toFloat() / peakRows.size

        val peakValues = peakRows.map { smoothed[it] }
        val maxPeak = peakValues.maxOrNull() ?: 0f
        val strongPeakRatio = if (peakRows.isEmpty() || maxPeak <= 0f) {
            0f
        } else {
            peakValues.count { it >= maxPeak * 0.55f }.toFloat() / peakRows.size
        }

        return PeakAnalysis(
            peakCount = peakRows.size,
            regularSpacing = spacing.regular,
            moderateSpacing = spacing.moderate,
            spacingConsistency = spacing.consistency,
            lowerHalfRatio = lowerHalfRatio,
            strongPeakRatio = strongPeakRatio,
        )
    }

    private fun smoothRowScores(scores: FloatArray, radius: Int): FloatArray {
        val out = FloatArray(scores.size)
        for (i in scores.indices) {
            var total = 0f
            var n = 0
            for (j in max(0, i - radius)..minOf(scores.lastIndex, i + radius)) {
                total += scores[j]
                n++
            }
            out[i] = if (n == 0) 0f else total / n
        }
        return out
    }

    private fun percentile(scores: FloatArray, p: Float): Float {
        val sorted = scores.sorted()
        val idx = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[idx]
    }

    private fun findPeaks(smoothed: FloatArray, threshold: Float, minGap: Int): List<Int> {
        val peakRows = mutableListOf<Int>()
        var lastPeakRow = -minGap
        for (i in smoothed.indices) {
            val isPeak = smoothed[i] > threshold &&
                (i == 0 || smoothed[i] >= smoothed[i - 1]) &&
                (i == smoothed.lastIndex || smoothed[i] >= smoothed[i + 1])
            if (isPeak && i - lastPeakRow >= minGap) {
                peakRows.add(i)
                lastPeakRow = i
            }
        }
        return peakRows
    }

    private data class SpacingMetrics(
        val consistency: Float,
        val regular: Boolean,
        val moderate: Boolean,
    )

    private fun spacingMetrics(peakRows: List<Int>): SpacingMetrics {
        if (peakRows.size < 3) return SpacingMetrics(0f, false, false)

        val gaps = peakRows.zipWithNext { a, b -> b - a }
        val meanGap = gaps.average()
        if (meanGap < MIN_PEAK_GAP_ROWS) return SpacingMetrics(0f, false, false)

        val medianGap = gaps.sorted()[gaps.size / 2].toDouble()
        val variance = gaps.sumOf { (it - meanGap) * (it - meanGap) } / gaps.size
        val stdDev = sqrt(variance)
        val regular = stdDev / meanGap < 0.48

        val withinMedian = gaps.count { abs(it - medianGap) <= medianGap * 0.45 }.toFloat() / gaps.size
        val moderate = withinMedian >= 0.50f && peakRows.size >= MIN_STEP_PEAKS

        return SpacingMetrics(
            consistency = withinMedian,
            regular = regular,
            moderate = moderate,
        )
    }

    companion object {
        private const val STABLE_MS = 900L
        private const val MIN_STEP_PEAKS = 4
        private const val MAX_STEP_PEAKS = 14
        private const val MIN_PEAK_GAP_ROWS = 7
        private const val SMOOTH_RADIUS = 5
    }
}
