package io.lazaro.pathguide

import kotlin.math.max

class ApproachEstimator {

    private var prevWidthNorm: Float? = null
    private var prevSeverity = 0f
    private var prevCloseRange = false
    private var velocityEma = 0f
    private var approachingMs = 0L

    fun update(
        openingWidthNorm: Float?,
        frontalSeverity: Float,
        frontalCloseRange: Boolean,
        deltaMs: Long,
    ): ApproachState {
        if (deltaMs <= 0) {
            return ApproachState(phase = phaseFor(velocityEma), velocity = velocityEma, stableMs = approachingMs)
        }

        val deltaSec = deltaMs / 1000f
        val widthVelocity = if (openingWidthNorm != null && prevWidthNorm != null) {
            (openingWidthNorm - prevWidthNorm!!) / deltaSec
        } else {
            0f
        }

        val severityVelocity = (frontalSeverity - prevSeverity) / deltaSec
        val closeRangePulse = if (frontalCloseRange && !prevCloseRange) 0.35f else 0f

        val instantVelocity = max(
            widthVelocity.coerceAtLeast(0f),
            max(
                severityVelocity.coerceAtLeast(0f),
                closeRangePulse,
            ),
        )

        velocityEma = velocityEma * 0.72f + instantVelocity * 0.28f

        if (velocityEma >= APPROACH_VELOCITY_THRESHOLD) {
            approachingMs += deltaMs
        } else {
            approachingMs = (approachingMs - deltaMs).coerceAtLeast(0L)
        }

        prevWidthNorm = openingWidthNorm
        prevSeverity = frontalSeverity
        prevCloseRange = frontalCloseRange

        val phase = phaseFor(velocityEma, frontalCloseRange, openingWidthNorm)
        val announceReady = approachingMs >= ANNOUNCE_STABLE_MS &&
            velocityEma >= APPROACH_VELOCITY_THRESHOLD &&
            phase != ApproachPhase.FAR

        return ApproachState(
            phase = phase,
            velocity = velocityEma,
            stableMs = approachingMs,
            announceReady = announceReady,
        )
    }

    fun reset() {
        prevWidthNorm = null
        prevSeverity = 0f
        prevCloseRange = false
        velocityEma = 0f
        approachingMs = 0L
    }

    private fun phaseFor(
        velocity: Float,
        closeRange: Boolean = false,
        openingWidthNorm: Float? = null,
    ): ApproachPhase {
        return when {
            closeRange || (openingWidthNorm != null && openingWidthNorm >= VERY_CLOSE_WIDTH_NORM) ->
                ApproachPhase.VERY_CLOSE
            velocity >= CLOSE_VELOCITY_THRESHOLD -> ApproachPhase.CLOSE
            velocity >= APPROACH_VELOCITY_THRESHOLD -> ApproachPhase.APPROACHING
            else -> ApproachPhase.FAR
        }
    }

    companion object {
        private const val APPROACH_VELOCITY_THRESHOLD = 0.05f
        private const val CLOSE_VELOCITY_THRESHOLD = 0.12f
        private const val ANNOUNCE_STABLE_MS = 550L
        private const val VERY_CLOSE_WIDTH_NORM = 0.44f
    }
}
