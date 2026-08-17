package io.lazaro.pathguide

import kotlin.math.abs

/**
 * Convierte error lateral del corredor transitable en pitidos espaciales proporcionales.
 *
 * Modelo mental: silencio = centrado y lejos de muros; pitido en el lado del desvío
 * o del muro cercano → alejarse del pitido. En giros (IMU) la proximidad lateral
 * pesa más porque la imagen suele fallar.
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

    /** true = latched en silencio (zona segura). */
    private var latchedSafe = true

    fun compute(
        walkable: WalkableCorridor,
        layout: StreetLayoutState,
        corridor: CorridorState,
        dangerLevel: SidewalkNotificationSystem.Level,
        yawRateDegPerSec: Float = 0f,
    ): Signal {
        if (dangerLevel == SidewalkNotificationSystem.Level.ROAD) {
            latchedSafe = false
            return roadDanger(layout)
        }
        if (dangerLevel == SidewalkNotificationSystem.Level.DRIFT) {
            latchedSafe = false
            return driftWarning(layout)
        }

        val turning = abs(yawRateDegPerSec) >= TURN_RATE_DEG_S

        val offsetSignal = if (walkable.confidence < 0.25f) {
            fallbackFromLayout(layout, corridor)
        } else {
            lateralFromOffset(walkable.lateralOffsetNorm)
        }

        // Muro lateral: pita aunque el offset diga "centrado" (caso típico al girar).
        val wallSignal = wallProximitySignal(corridor, turning)
        val imuHint = imuTurnWallHint(corridor, yawRateDegPerSec)

        return mergeLateral(offsetSignal, wallSignal, imuHint, turning)
    }

    fun frontalBoost(
        signal: Signal,
        frontalDistanceM: Float?,
        frontalSeverity: Float,
    ): Signal {
        val effectiveDistanceM = resolveFrontalDistanceM(frontalDistanceM, frontalSeverity)
        if (effectiveDistanceM != null && effectiveDistanceM <= FRONTAL_INSISTENT_M) {
            val urgency = ((FRONTAL_INSISTENT_M - effectiveDistanceM) / FRONTAL_INSISTENT_M)
                .coerceIn(0f, 1f)
            val intensity = (0.85f + urgency * 0.13f).coerceIn(0.85f, 0.98f)
            return Signal(
                leftBeep = intensity,
                rightBeep = intensity,
                continuous = true,
                warning = true,
                inSafeZone = false,
                guidanceMode = true,
            )
        }

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

    fun reset() {
        latchedSafe = true
    }

    private fun wallProximitySignal(corridor: CorridorState, turning: Boolean): Signal {
        val activate = if (turning) WALL_ACTIVATE_TURNING else WALL_ACTIVATE
        val left = proximityToBeep(corridor.leftProximity, activate, turning)
        val right = proximityToBeep(corridor.rightProximity, activate, turning)
        if (left <= 0f && right <= 0f) {
            return Signal(guidanceMode = true, inSafeZone = true)
        }
        return Signal(
            leftBeep = left,
            rightBeep = right,
            continuous = true,
            guidanceMode = true,
            inSafeZone = false,
        )
    }

    /**
     * Si giras fuerte y un lado ya está "ocupado", refuerza ese oído:
     * la cámara suele retrasarse y te comes la pared.
     */
    private fun imuTurnWallHint(corridor: CorridorState, yawRateDegPerSec: Float): Signal {
        val rate = abs(yawRateDegPerSec)
        if (rate < TURN_RATE_DEG_S) return Signal(guidanceMode = true, inSafeZone = true)

        val urgency = ((rate - TURN_RATE_DEG_S) / 80f).coerceIn(0f, 1f)
        val leftP = corridor.leftProximity
        val rightP = corridor.rightProximity
        val dominant = maxOf(leftP, rightP)
        if (dominant < WALL_ACTIVATE_TURNING) {
            return Signal(guidanceMode = true, inSafeZone = true)
        }

        val intensity = (0.40f + urgency * 0.45f + dominant * 0.20f).coerceIn(0.40f, 0.95f)
        return if (rightP >= leftP) {
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

    private fun proximityToBeep(proximity: Float, activate: Float, turning: Boolean): Float {
        if (proximity < activate) return 0f
        val span = (1f - activate).coerceAtLeast(0.15f)
        val magnitude = ((proximity - activate) / span).coerceIn(0f, 1f)
        val base = if (turning) 0.38f else 0.28f
        val gain = if (turning) 0.55f else 0.50f
        return (base + magnitude * gain).coerceIn(base, 0.92f)
    }

    private fun mergeLateral(
        offset: Signal,
        wall: Signal,
        imuHint: Signal,
        turning: Boolean,
    ): Signal {
        if (offset.warning) return offset

        val left = maxOf(offset.leftBeep, wall.leftBeep, imuHint.leftBeep)
        val right = maxOf(offset.rightBeep, wall.rightBeep, imuHint.rightBeep)
        val any = left > 0.05f || right > 0.05f
        if (!any) {
            return Signal(
                leftBeep = 0f,
                rightBeep = 0f,
                continuous = false,
                inSafeZone = true,
                guidanceMode = true,
            )
        }

        // En giro, un solo oído dominante evita confusión L+R.
        var outL = left
        var outR = right
        if (turning && left > 0.05f && right > 0.05f) {
            if (right >= left) outL = 0f else outR = 0f
        }

        return Signal(
            leftBeep = outL,
            rightBeep = outR,
            continuous = true,
            warning = false,
            inSafeZone = false,
            guidanceMode = true,
        )
    }

    private fun lateralFromOffset(offset: Float): Signal {
        val absOffset = abs(offset)
        val safe = if (latchedSafe) {
            absOffset <= EXIT_SILENCE
        } else {
            absOffset < ENTER_SILENCE
        }
        latchedSafe = safe

        if (safe) {
            return Signal(
                leftBeep = 0f,
                rightBeep = 0f,
                continuous = false,
                warning = false,
                inSafeZone = true,
                guidanceMode = true,
            )
        }

        val magnitude = ((absOffset - ENTER_SILENCE) / (1f - ENTER_SILENCE)).coerceIn(0f, 1f)
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
            if (max < 0.18f) {
                latchedSafe = true
                return Signal(inSafeZone = true)
            }
            latchedSafe = false
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

        return lateralFromOffset(offset)
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
        const val DEADBAND = 0.20f
        const val ENTER_SILENCE = 0.20f
        const val EXIT_SILENCE = 0.24f
        const val FRONTAL_INSISTENT_M = 2.6f
        const val FRONTAL_SEVERITY_INSISTENT = 0.42f

        /** Proximidad lateral que dispara pitido (parado / andando recto). */
        const val WALL_ACTIVATE = 0.48f

        /** Más sensible al girar (la imagen falla). */
        const val WALL_ACTIVATE_TURNING = 0.36f

        /** |yaw rate| por encima → modo giro (visión menos fiable). */
        const val TURN_RATE_DEG_S = 28f

        fun resolveFrontalDistanceM(
            frontalDistanceM: Float?,
            frontalSeverity: Float,
        ): Float? {
            if (frontalDistanceM != null && frontalDistanceM > 0f) {
                return frontalDistanceM
            }
            if (frontalSeverity < FRONTAL_SEVERITY_INSISTENT) return null
            val t = ((frontalSeverity - FRONTAL_SEVERITY_INSISTENT) /
                (1f - FRONTAL_SEVERITY_INSISTENT)).coerceIn(0f, 1f)
            return FRONTAL_INSISTENT_M - t * 2.5f
        }
    }
}
