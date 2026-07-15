package io.lazaro.pathguide

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class DoorwayDetector {

    private var emaConfidence = 0f
    private var emaLeft = 0f
    private var emaRight = 0f
    private var trackedLeftNorm = 0f
    private var trackedRightNorm = 1f
    private var trackedCenterNorm = 0.5f
    private var trackedWidthNorm = 0f
    private var hasTrackedGeometry = false
    private var consistentFrames = 0

    fun detect(gray: ByteArray, width: Int, height: Int): DoorwayState {
        if (width < 40 || height < 40) return DoorwayState()

        val roiTop = (height * PathGuideRoi.DOORWAY_TOP).toInt()
        val roiBottom = (height * PathGuideRoi.DOORWAY_BOTTOM).toInt()
        if (roiBottom <= roiTop) return DoorwayState()

        val columnScores = FloatArray(width)
        for (x in 1 until width - 1) {
            columnScores[x] = verticalEdgeColumn(gray, width, height, x, roiTop, roiBottom)
        }
        val smoothed = smoothColumns(columnScores, radius = 5)

        val leftSearchEnd = (width * 0.46f).toInt()
        val rightSearchStart = (width * 0.54f).toInt()
        val minCol = (width * 0.10f).toInt()
        val maxCol = (width * 0.90f).toInt()

        val leftJamb = findBestPeak(smoothed, minCol, leftSearchEnd)
        val rightJamb = findBestPeak(smoothed, rightSearchStart, maxCol)
        if (leftJamb < 0 || rightJamb < 0) {
            decay()
            return partialState()
        }

        val openingWidth = rightJamb - leftJamb
        val openingWidthNorm = openingWidth.toFloat() / width
        if (openingWidthNorm < MIN_OPENING_NORM || openingWidthNorm > MAX_OPENING_NORM) {
            decay()
            return partialState()
        }

        val leftScore = smoothed[leftJamb]
        val rightScore = smoothed[rightJamb]
        if (!jambPairValid(leftScore, rightScore)) {
            decay()
            return partialState()
        }

        val openingInnerStart = leftJamb + (openingWidth * 0.25f).toInt()
        val openingInnerEnd = rightJamb - (openingWidth * 0.25f).toInt()
        val openingScore = if (openingInnerEnd > openingInnerStart) {
            average(smoothed, openingInnerStart, openingInnerEnd)
        } else {
            leftScore
        }

        val jambStrength = (leftScore + rightScore) / 2f
        val contrast = if (openingScore <= 0f) 0f else (jambStrength - openingScore) / jambStrength

        val rawConfidence = when {
            contrast >= 0.38f && jambStrength >= 16f -> 0.95f
            contrast >= 0.26f && jambStrength >= 12f -> 0.72f
            contrast >= 0.18f && jambStrength >= 9f -> 0.48f
            else -> 0f
        }

        if (rawConfidence < 0.48f) {
            decay()
            return partialState()
        }

        val leftNorm = leftJamb.toFloat() / width
        val rightNorm = rightJamb.toFloat() / width
        val centerNorm = (leftNorm + rightNorm) / 2f

        if (!geometryConsistent(leftNorm, rightNorm, centerNorm, openingWidthNorm)) {
            decay()
            return partialState()
        }

        consistentFrames++
        emaConfidence = emaConfidence * 0.82f + rawConfidence * 0.18f
        val detected = emaConfidence >= DETECT_THRESHOLD && consistentFrames >= 3

        if (!detected) {
            emaLeft *= 0.85f
            emaRight *= 0.85f
            return DoorwayState(confidence = emaConfidence)
        }

        updateTrackedGeometry(leftNorm, rightNorm, centerNorm, openingWidthNorm)

        val approachFactor = approachFactor(trackedWidthNorm)
        val frameCenter = 0.5f
        val distLeft = frameCenter - trackedLeftNorm
        val distRight = trackedRightNorm - frameCenter
        val halfOpening = trackedWidthNorm / 2f

        val centerTolerance = CENTER_TOLERANCE_BASE + approachFactor * CENTER_TOLERANCE_APPROACH
        val balance = (distLeft - distRight) / trackedWidthNorm

        var rawLeft = 0f
        var rawRight = 0f

        if (balance < -centerTolerance) {
            val span = 0.5f - centerTolerance
            rawLeft = ((-balance - centerTolerance) / span).coerceIn(0f, 1f)
        }
        if (balance > centerTolerance) {
            val span = 0.5f - centerTolerance
            rawRight = ((balance - centerTolerance) / span).coerceIn(0f, 1f)
        }

        val nearScale = 0.55f + approachFactor * 0.35f
        val nearLeftJamb = (1f - distLeft / (halfOpening * nearScale)).coerceIn(0f, 1f)
        val nearRightJamb = (1f - distRight / (halfOpening * nearScale)).coerceIn(0f, 1f)
        rawLeft = max(rawLeft, nearLeftJamb * 0.5f)
        rawRight = max(rawRight, nearRightJamb * 0.5f)

        val proximityDamping = 1f - approachFactor * PROXIMITY_DAMPING
        rawLeft *= proximityDamping
        rawRight *= proximityDamping

        emaLeft = emaLeft * 0.72f + rawLeft * 0.28f
        emaRight = emaRight * 0.72f + rawRight * 0.28f

        val centeredThreshold = CENTERED_PROXIMITY + approachFactor * CENTERED_PROXIMITY_APPROACH

        return DoorwayState(
            detected = true,
            confidence = emaConfidence,
            leftJambNorm = trackedLeftNorm,
            rightJambNorm = trackedRightNorm,
            centerNorm = trackedCenterNorm,
            openingWidthNorm = trackedWidthNorm,
            approachFactor = approachFactor,
            leftProximity = emaLeft,
            rightProximity = emaRight,
            isCentered = emaLeft < centeredThreshold && emaRight < centeredThreshold,
        )
    }

    fun reset() {
        emaConfidence = 0f
        emaLeft = 0f
        emaRight = 0f
        trackedLeftNorm = 0f
        trackedRightNorm = 1f
        trackedCenterNorm = 0.5f
        trackedWidthNorm = 0f
        hasTrackedGeometry = false
        consistentFrames = 0
    }

    private fun partialState(): DoorwayState {
        return DoorwayState(confidence = emaConfidence)
    }

    private fun decay() {
        emaConfidence *= 0.88f
        emaLeft *= 0.88f
        emaRight *= 0.88f
        consistentFrames = max(0, consistentFrames - 1)
    }

    private fun jambPairValid(leftScore: Float, rightScore: Float): Boolean {
        if (leftScore < 8f || rightScore < 8f) return false
        val ratio = leftScore / rightScore
        return ratio in 0.45f..2.2f
    }

    private fun geometryConsistent(
        leftNorm: Float,
        rightNorm: Float,
        centerNorm: Float,
        widthNorm: Float,
    ): Boolean {
        if (!hasTrackedGeometry) return true

        val maxJump = 0.08f + approachFactor(trackedWidthNorm) * 0.10f
        val leftJump = abs(leftNorm - trackedLeftNorm)
        val rightJump = abs(rightNorm - trackedRightNorm)
        val widthJump = abs(widthNorm - trackedWidthNorm)

        return leftJump < maxJump && rightJump < maxJump && widthJump < maxJump + 0.06f &&
            abs(centerNorm - trackedCenterNorm) < maxJump
    }

    private fun updateTrackedGeometry(
        leftNorm: Float,
        rightNorm: Float,
        centerNorm: Float,
        widthNorm: Float,
    ) {
        val approach = approachFactor(widthNorm)
        val alpha = if (!hasTrackedGeometry) {
            1f
        } else {
            (0.22f - approach * 0.10f).coerceIn(0.10f, 0.22f)
        }

        trackedLeftNorm = lerp(trackedLeftNorm, leftNorm, alpha)
        trackedRightNorm = lerp(trackedRightNorm, rightNorm, alpha)
        trackedCenterNorm = lerp(trackedCenterNorm, centerNorm, alpha)
        trackedWidthNorm = lerp(trackedWidthNorm, widthNorm, alpha)
        hasTrackedGeometry = true
    }

    private fun approachFactor(openingWidthNorm: Float): Float {
        return ((openingWidthNorm - APPROACH_WIDTH_MIN) / (APPROACH_WIDTH_MAX - APPROACH_WIDTH_MIN))
            .coerceIn(0f, 1f)
    }

    private fun lerp(from: Float, to: Float, alpha: Float): Float {
        return from + (to - from) * alpha
    }

    private fun verticalEdgeColumn(
        gray: ByteArray,
        width: Int,
        height: Int,
        x: Int,
        roiTop: Int,
        roiBottom: Int,
    ): Float {
        var sum = 0
        var count = 0
        for (y in roiTop until roiBottom) {
            val idx = y * width + x
            if (idx <= 0 || idx + 1 >= gray.size) continue
            val value = gray[idx].toInt() and 0xFF
            val left = gray[idx - 1].toInt() and 0xFF
            val right = gray[idx + 1].toInt() and 0xFF
            sum += abs(value - left) + abs(value - right)
            count++
        }
        return if (count == 0) 0f else sum.toFloat() / count
    }

    private fun smoothColumns(scores: FloatArray, radius: Int): FloatArray {
        val out = FloatArray(scores.size)
        for (i in scores.indices) {
            var total = 0f
            var n = 0
            for (j in max(0, i - radius)..min(scores.lastIndex, i + radius)) {
                total += scores[j]
                n++
            }
            out[i] = if (n == 0) 0f else total / n
        }
        return out
    }

    private fun findBestPeak(scores: FloatArray, start: Int, end: Int): Int {
        if (end <= start) return -1
        var bestIdx = -1
        var bestScore = 0f
        val localMin = scores.sliceArray(start until end).maxOrNull()?.times(0.55f) ?: 0f
        for (i in start until end) {
            val left = scores.getOrElse(i - 1) { 0f }
            val right = scores.getOrElse(i + 1) { 0f }
            val isPeak = scores[i] >= left && scores[i] >= right && scores[i] >= localMin
            if (isPeak && scores[i] > bestScore) {
                bestScore = scores[i]
                bestIdx = i
            }
        }
        return bestIdx
    }

    private fun average(scores: FloatArray, start: Int, end: Int): Float {
        if (end <= start) return 0f
        var total = 0f
        for (i in start until end) total += scores[i]
        return total / (end - start)
    }

    companion object {
        private const val DETECT_THRESHOLD = 0.60f
        private const val MIN_OPENING_NORM = 0.14f
        private const val MAX_OPENING_NORM = 0.72f
        private const val CENTER_TOLERANCE_BASE = 0.14f
        private const val CENTER_TOLERANCE_APPROACH = 0.14f
        private const val PROXIMITY_DAMPING = 0.72f
        private const val CENTERED_PROXIMITY = 0.16f
        private const val CENTERED_PROXIMITY_APPROACH = 0.10f
        private const val APPROACH_WIDTH_MIN = 0.16f
        private const val APPROACH_WIDTH_MAX = 0.52f
    }
}
