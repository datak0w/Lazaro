package io.lazaro.pathguide

import kotlin.math.abs

/**
 * Convierte error lateral del corredor transitable en pitidos espaciales proporcionales.
 */
class LateralGuidanceController {

    data class Signal(
        val leftBeep: Float = 0f,
        val rightBeep: Float = 0f,
        val continuous: Boolean = false,
        val warning: Boolean = false,
        val inSafeZone: Boolean = false,
        val guidanceMode: Boolean = false,
    )

    fun compute(
        walkable: WalkableCorridor,
        layout: StreetLayoutState,
        corridor: CorridorState,
        dangerLevel: SidewalkNotificationSystem.Level,
    ): Signal {
        if (dangerLevel == SidewalkNotificationSystem.Level.ROAD) {
            return roadDanger(layout)
        }
        if (dangerLevel == SidewalkNotificationSystem.Level.DRIFT) {
            return driftWarning(layout)
        }

        if (walkable.confidence < 0.25f) {
            return fallbackFromLayout(layout, corridor)
        }

        val offset = walkable.lateralOffsetNorm
        if (abs(offset) < DEADBAND) {
            return Signal(inSafeZone = true, guidanceMode = true)
        }

        val magnitude = ((abs(offset) - DEADBAND) / (1f - DEADBAND)).coerceIn(0f, 1f)
        val intensity = (0.22f + magnitude * 0.68f).coerceIn(0.22f, 0.90f)

        return if (offset > 0f) {
            Signal(
                rightBeep = intensity,
                continuous = true,
                guidanceMode = true,
                inSafeZone = false,
            )
        } else {
            Signal(
                leftBeep = intensity,
                continuous = true,
                guidanceMode = true,
                inSafeZone = false,
            )
        }
    }

    fun frontalBoost(
        signal: Signal,
        frontalDistanceM: Float?,
        frontalSeverity: Float,
    ): Signal {
        val proximity = when {
            frontalDistanceM != null && frontalDistanceM > 0f ->
                (1.2f / frontalDistanceM.coerceAtLeast(0.35f)).coerceIn(0f, 1f)
            frontalSeverity > 0.18f -> frontalSeverity.coerceIn(0f, 1f)
            else -> return signal
        }
        if (proximity < 0.25f) return signal

        val boost = (proximity * 0.35f).coerceIn(0f, 0.35f)
        return signal.copy(
            leftBeep = (signal.leftBeep + boost).coerceAtMost(1f),
            rightBeep = (signal.rightBeep + boost).coerceAtMost(1f),
            continuous = true,
            guidanceMode = true,
        )
    }

    private fun roadDanger(layout: StreetLayoutState): Signal {
        val (left, right) = turnTowardSafeBeeps(layout.safeSide, 0.95f)
        return Signal(
            leftBeep = if (layout.safeSide == RoadSide.UNKNOWN) 0.95f else left.coerceAtLeast(0.75f),
            rightBeep = if (layout.safeSide == RoadSide.UNKNOWN) 0f else right.coerceAtLeast(0.75f),
            continuous = true,
            warning = true,
            inSafeZone = false,
        )
    }

    private fun driftWarning(layout: StreetLayoutState): Signal {
        val intensity = (layout.driftScore * 0.45f + 0.55f).coerceIn(0.60f, 0.95f)
        val (left, right) = turnTowardSafeBeeps(layout.safeSide, intensity)
        return Signal(
            leftBeep = left,
            rightBeep = right,
            continuous = true,
            warning = true,
            inSafeZone = false,
        )
    }

    private fun fallbackFromLayout(
        layout: StreetLayoutState,
        corridor: CorridorState,
    ): Signal {
        if (layout.alignment == SidewalkAlignment.UNKNOWN) {
            val left = corridor.leftProximity
            val right = corridor.rightProximity
            val max = maxOf(left, right)
            if (max < 0.18f) return Signal(inSafeZone = true)
            return Signal(
                leftBeep = left.coerceIn(0f, 0.85f),
                rightBeep = right.coerceIn(0f, 0.85f),
                continuous = max >= 0.35f,
                guidanceMode = true,
            )
        }

        val offset = when (layout.safeSide) {
            RoadSide.LEFT -> (0.64f - layout.centeringScore) * 1.4f
            RoadSide.RIGHT -> (layout.centeringScore - 0.36f) * 1.4f
            RoadSide.UNKNOWN -> 0f
        }.coerceIn(-1f, 1f)

        if (abs(offset) < DEADBAND) {
            return Signal(inSafeZone = true, guidanceMode = true)
        }
        val magnitude = ((abs(offset) - DEADBAND) / (1f - DEADBAND)).coerceIn(0f, 1f)
        val intensity = (0.25f + magnitude * 0.65f).coerceIn(0.25f, 0.88f)
        return if (offset > 0f) {
            Signal(rightBeep = intensity, continuous = true, guidanceMode = true)
        } else {
            Signal(leftBeep = intensity, continuous = true, guidanceMode = true)
        }
    }

    private fun turnTowardSafeBeeps(safeSide: RoadSide, intensity: Float): Pair<Float, Float> {
        val i = intensity.coerceIn(0.50f, 0.98f)
        return when (safeSide) {
            RoadSide.LEFT -> i to 0f
            RoadSide.RIGHT -> 0f to i
            RoadSide.UNKNOWN -> i to 0f
        }
    }

    companion object {
        const val DEADBAND = 0.12f
    }
}
