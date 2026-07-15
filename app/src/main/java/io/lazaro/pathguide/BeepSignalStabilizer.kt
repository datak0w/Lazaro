package io.lazaro.pathguide

import kotlin.math.abs

/**
 * Evita pitidos contradictorios L/R cuando las señales de proximidad oscilan.
 * Solo suena el lado dominante (obstáculo más cercano) con histéresis.
 */
class BeepSignalStabilizer {

    private var emaLeft = 0f
    private var emaRight = 0f
    private var latchedSide = SIDE_NONE
    private var latchedSinceMs = 0L

    fun stabilize(left: Float, right: Float, doorwayMode: Boolean): Pair<Float, Float> {
        val alpha = if (doorwayMode) 0.24f else 0.20f
        emaLeft = emaLeft * (1f - alpha) + left.coerceIn(0f, 1f) * alpha
        emaRight = emaRight * (1f - alpha) + right.coerceIn(0f, 1f) * alpha

        val maxSignal = maxOf(emaLeft, emaRight)
        if (maxSignal < SILENCE_THRESHOLD) {
            latchedSide = SIDE_NONE
            return 0f to 0f
        }

        val diff = emaLeft - emaRight
        val deadZone = if (doorwayMode) DEAD_ZONE_DOORWAY else DEAD_ZONE_CORRIDOR

        if (abs(diff) < deadZone) {
            latchedSide = SIDE_NONE
            return 0f to 0f
        }

        val targetSide = if (diff > 0f) SIDE_LEFT else SIDE_RIGHT
        val now = System.currentTimeMillis()
        val margin = abs(diff)

        val shouldSwitch = latchedSide == SIDE_NONE ||
            targetSide == latchedSide ||
            now - latchedSinceMs >= MIN_HOLD_MS ||
            margin >= SWITCH_MARGIN

        if (shouldSwitch && targetSide != latchedSide) {
            latchedSide = targetSide
            latchedSinceMs = now
        }

        return when (latchedSide) {
            SIDE_LEFT -> emaLeft to 0f
            SIDE_RIGHT -> 0f to emaRight
            else -> 0f to 0f
        }
    }

    fun dominantSide(left: Float, right: Float): Int {
        val diff = left - right
        return when {
            abs(diff) < DEAD_ZONE_CORRIDOR -> SIDE_NONE
            diff > 0f -> SIDE_LEFT
            else -> SIDE_RIGHT
        }
    }

    fun reset() {
        emaLeft = 0f
        emaRight = 0f
        latchedSide = SIDE_NONE
        latchedSinceMs = 0L
    }

    companion object {
        const val SIDE_NONE = -1
        const val SIDE_LEFT = 0
        const val SIDE_RIGHT = 1

        private const val SILENCE_THRESHOLD = 0.14f
        private const val DEAD_ZONE_CORRIDOR = 0.13f
        private const val DEAD_ZONE_DOORWAY = 0.09f
        private const val SWITCH_MARGIN = 0.20f
        private const val MIN_HOLD_MS = 900L
    }
}
