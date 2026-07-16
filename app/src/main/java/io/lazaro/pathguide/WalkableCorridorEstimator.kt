package io.lazaro.pathguide

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Estima el centro del corredor transitable (acera) a partir de visión monocular
 * y, si está disponible, perfil de profundidad ARCore / distancia LDAF.
 */
class WalkableCorridorEstimator {

    private var emaLeftEdge = 0.22f
    private var emaRightEdge = 0.78f
    private var emaOffset = 0f

    fun reset() {
        emaLeftEdge = 0.22f
        emaRightEdge = 0.78f
        emaOffset = 0f
    }

    fun estimate(
        gray: ByteArray,
        width: Int,
        height: Int,
        layout: StreetLayoutState,
        depth: DepthSnapshot = DepthSnapshot(),
    ): WalkableCorridor {
        val mono = estimateMonocular(gray, width, height, layout)
        if (!depth.available || depth.profile == null) {
            val frontal = depth.frontalDistanceM ?: mono.frontalDistanceM
            return mono.copy(
                frontalDistanceM = frontal,
                source = if (depth.frontalDistanceM != null) {
                    PerceptionSource.FUSED
                } else {
                    PerceptionSource.MONOCULAR
                },
            )
        }

        val depthCorridor = estimateFromDepthProfile(depth.profile, layout)
        if (depthCorridor.confidence < 0.35f) {
            return mono.copy(
                frontalDistanceM = depth.frontalDistanceM ?: mono.frontalDistanceM,
                source = PerceptionSource.FUSED,
            )
        }

        val blend = depthCorridor.confidence.coerceIn(0.35f, 0.92f)
        val left = lerp(mono.leftEdgeNorm, depthCorridor.leftEdgeNorm, blend)
        val right = lerp(mono.rightEdgeNorm, depthCorridor.rightEdgeNorm, blend)
        val center = (left + right) * 0.5f
        val half = ((right - left) * 0.5f).coerceAtLeast(0.08f)
        val offset = ((center - 0.5f) / half).coerceIn(-1f, 1f)
        val smoothOffset = smooth(emaOffset, offset, OFFSET_ALPHA)
        emaOffset = smoothOffset

        return WalkableCorridor(
            lateralOffsetNorm = smoothOffset,
            leftEdgeNorm = left,
            rightEdgeNorm = right,
            corridorWidthNorm = right - left,
            corridorWidthM = depthCorridor.corridorWidthM,
            frontalDistanceM = depth.frontalDistanceM ?: depthCorridor.frontalDistanceM,
            safeSide = layout.safeSide,
            confidence = max(mono.confidence, depthCorridor.confidence) * blend,
            source = PerceptionSource.FUSED,
        )
    }

    private fun estimateMonocular(
        gray: ByteArray,
        width: Int,
        height: Int,
        layout: StreetLayoutState,
    ): WalkableCorridor {
        val roiTop = (height * ROI_TOP).toInt()
        val roiBottom = (height * ROI_BOTTOM).toInt().coerceAtMost(height - 2)
        val columnScores = FloatArray(COLUMN_COUNT)
        val edgeScores = FloatArray(COLUMN_COUNT)

        for (col in 0 until COLUMN_COUNT) {
            val x = ((col + 0.5f) / COLUMN_COUNT * width).toInt().coerceIn(1, width - 2)
            var groundUniformity = 0f
            var verticalEdge = 0f
            var samples = 0
            var y = roiTop
            while (y < roiBottom) {
                val idx = y * width + x
                val above = gray[idx - width].toInt() and 0xFF
                val cur = gray[idx].toInt() and 0xFF
                val below = gray[idx + width].toInt() and 0xFF
                val left = gray[idx - 1].toInt() and 0xFF
                val right = gray[idx + 1].toInt() and 0xFF
                verticalEdge += abs(cur - above) + abs(cur - below)
                groundUniformity += 255f - abs(left - right)
                samples++
                y += 2
            }
            if (samples > 0) {
                columnScores[col] = groundUniformity / samples
                edgeScores[col] = verticalEdge / samples
            }
        }

        smoothArray(columnScores, 3)
        smoothArray(edgeScores, 3)

        val combined = FloatArray(COLUMN_COUNT) { i ->
            columnScores[i] * 0.55f + (255f - edgeScores[i]) * 0.45f
        }

        val (leftCol, rightCol) = findWalkableSpan(
            combined = combined,
            edgeScores = edgeScores,
            layout = layout,
        )

        val leftNorm = leftCol / COLUMN_COUNT.toFloat()
        val rightNorm = (rightCol + 1) / COLUMN_COUNT.toFloat()
        val smoothLeft = smooth(emaLeftEdge, leftNorm.coerceIn(0.04f, 0.48f), EDGE_ALPHA)
        val smoothRight = smooth(emaRightEdge, rightNorm.coerceIn(0.52f, 0.96f), EDGE_ALPHA)
        emaLeftEdge = smoothLeft
        emaRightEdge = smoothRight

        val center = (smoothLeft + smoothRight) * 0.5f
        val half = ((smoothRight - smoothLeft) * 0.5f).coerceAtLeast(0.08f)
        val rawOffset = ((center - 0.5f) / half).coerceIn(-1f, 1f)
        val smoothOffset = smooth(emaOffset, rawOffset, OFFSET_ALPHA)
        emaOffset = smoothOffset

        val edgeContrast = edgeScores[leftCol] + edgeScores[rightCol.coerceAtMost(COLUMN_COUNT - 1)]
        val confidence = ((smoothRight - smoothLeft) * 1.6f + (1f - edgeContrast / 400f))
            .coerceIn(0.2f, 1f)

        return WalkableCorridor(
            lateralOffsetNorm = smoothOffset,
            leftEdgeNorm = smoothLeft,
            rightEdgeNorm = smoothRight,
            corridorWidthNorm = smoothRight - smoothLeft,
            safeSide = layout.safeSide,
            confidence = confidence,
            source = PerceptionSource.MONOCULAR,
        )
    }

    private fun estimateFromDepthProfile(
        profile: DepthColumnProfile,
        layout: StreetLayoutState,
    ): WalkableCorridor {
        val cols = profile.columns
        if (cols < 8) {
            return WalkableCorridor(confidence = 0f, safeSide = layout.safeSide)
        }

        val groundDepth = medianGroundDepth(profile) ?: return WalkableCorridor(
            confidence = 0f,
            safeSide = layout.safeSide,
        )

        val walkable = BooleanArray(cols)
        var walkableCount = 0
        for (i in 0 until cols) {
            val depth = profile.depthMm[i]
            val conf = profile.confidence[i]
            val isGround = depth > 0f &&
                conf >= 0.35f &&
                abs(depth - groundDepth) <= GROUND_TOLERANCE_MM
            walkable[i] = isGround
            if (isGround) walkableCount++
        }

        if (walkableCount < cols / 5) {
            return WalkableCorridor(confidence = 0f, safeSide = layout.safeSide)
        }

        var left = 0
        var right = cols - 1
        while (left < cols && !walkable[left]) left++
        while (right > left && !walkable[right]) right--
        if (right - left < 3) {
            return WalkableCorridor(confidence = 0f, safeSide = layout.safeSide)
        }

        val leftNorm = left / cols.toFloat()
        val rightNorm = (right + 1) / cols.toFloat()
        val center = (leftNorm + rightNorm) * 0.5f
        val half = ((rightNorm - leftNorm) * 0.5f).coerceAtLeast(0.08f)
        val offset = ((center - 0.5f) / half).coerceIn(-1f, 1f)

        val frontal = profile.depthMm
            .slice((cols * 0.42f).toInt()..(cols * 0.58f).toInt())
            .filter { it > 0f }
            .minOrNull()
            ?.div(1000f)

        val widthM = (rightNorm - leftNorm) * 2.2f

        return WalkableCorridor(
            lateralOffsetNorm = offset,
            leftEdgeNorm = leftNorm,
            rightEdgeNorm = rightNorm,
            corridorWidthNorm = rightNorm - leftNorm,
            corridorWidthM = widthM,
            frontalDistanceM = frontal,
            safeSide = layout.safeSide,
            confidence = (walkableCount / cols.toFloat()).coerceIn(0.35f, 0.95f),
            source = PerceptionSource.DEPTH,
        )
    }

    private fun findWalkableSpan(
        combined: FloatArray,
        edgeScores: FloatArray,
        layout: StreetLayoutState,
    ): Pair<Int, Int> {
        val maxScore = combined.maxOrNull() ?: 0f
        val threshold = maxScore * 0.72f
        var bestLeft = (COLUMN_COUNT * 0.22f).toInt()
        var bestRight = (COLUMN_COUNT * 0.78f).toInt()
        var bestWidth = 0

        var left = 0
        while (left < COLUMN_COUNT) {
            while (left < COLUMN_COUNT && combined[left] < threshold) left++
            if (left >= COLUMN_COUNT) break
            var right = left
            while (right + 1 < COLUMN_COUNT && combined[right + 1] >= threshold * 0.88f) {
                right++
            }
            val width = right - left
            val center = (left + right) * 0.5f / COLUMN_COUNT
            val bias = when (layout.safeSide) {
                RoadSide.LEFT -> if (center < 0.55f) 1.15f else 0.92f
                RoadSide.RIGHT -> if (center > 0.45f) 1.15f else 0.92f
                RoadSide.UNKNOWN -> 1f
            }
            val score = (width * bias).toInt()
            if (score > bestWidth) {
                bestWidth = score
                bestLeft = refineEdge(left, edgeScores, searchLeft = true)
                bestRight = refineEdge(right, edgeScores, searchLeft = false)
            }
            left = right + 1
        }

        if (bestWidth == 0) {
            bestLeft = (COLUMN_COUNT * layout.sidewalkLeftNorm).toInt().coerceIn(0, COLUMN_COUNT - 2)
            bestRight = (COLUMN_COUNT * layout.sidewalkRightNorm).toInt().coerceIn(bestLeft + 1, COLUMN_COUNT - 1)
        }

        return bestLeft.coerceIn(0, COLUMN_COUNT - 2) to
            bestRight.coerceIn(bestLeft + 1, COLUMN_COUNT - 1)
    }

    private fun refineEdge(col: Int, edgeScores: FloatArray, searchLeft: Boolean): Int {
        val start = col.coerceIn(1, COLUMN_COUNT - 2)
        var best = start
        var bestEdge = edgeScores[start]
        val range = if (searchLeft) (start - 2).coerceAtLeast(0)..start else start..(start + 2).coerceAtMost(COLUMN_COUNT - 1)
        for (i in range) {
            if (edgeScores[i] > bestEdge) {
                bestEdge = edgeScores[i]
                best = i
            }
        }
        return best
    }

    private fun medianGroundDepth(profile: DepthColumnProfile): Float? {
        val samples = profile.depthMm
            .filterIndexed { index, depth ->
                depth > 0f && profile.confidence[index] >= 0.35f
            }
            .sorted()
        if (samples.size < profile.columns / 4) return null
        return samples[samples.size / 2]
    }

    private fun smooth(previous: Float, current: Float, alpha: Float): Float {
        return previous * (1f - alpha) + current * alpha
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun smoothArray(values: FloatArray, radius: Int) {
        val copy = values.copyOf()
        for (i in values.indices) {
            var sum = 0f
            var count = 0
            for (j in (i - radius)..(i + radius)) {
                if (j in copy.indices) {
                    sum += copy[j]
                    count++
                }
            }
            values[i] = sum / count
        }
    }

    companion object {
        private const val COLUMN_COUNT = 48
        private const val ROI_TOP = 0.58f
        private const val ROI_BOTTOM = 0.94f
        private const val EDGE_ALPHA = 0.28f
        private const val OFFSET_ALPHA = 0.32f
        private const val GROUND_TOLERANCE_MM = 180f
    }
}
