package io.lazaro.pathguide

/**
 * Guía de acera **solo con pitidos espaciales** (sin anuncios de voz).
 *
 * - Zona SAFE: silencio.
 * - Desvío / calzada: pita el oído del lado **hacia el que hay que girar**.
 */
object SidewalkNotificationSystem {

    enum class Level {
        OK,
        DRIFT,
        ROAD,
    }

    data class Signal(
        val level: Level,
        val leftBeep: Float = 0f,
        val rightBeep: Float = 0f,
        val continuous: Boolean = false,
        val warning: Boolean = false,
        val voiceCue: DoorwayVoiceCue? = null,
        val haptic: Haptic = Haptic.NONE,
        val inSafeZone: Boolean = false,
    ) {
        enum class Haptic {
            NONE,
            NUDGE_LEFT,
            NUDGE_RIGHT,
            ROAD_DANGER,
            RECOVERED,
        }
    }

    data class EvalResult(
        val signal: Signal,
        val hugAnnounced: Boolean,
    )

    fun evaluate(
        layout: StreetLayoutState,
        corridor: CorridorState,
        now: Long,
        lastVoiceMs: Long,
        lastRecoveredMs: Long,
        wasOffSidewalk: Boolean,
        hugAnnounced: Boolean,
    ): EvalResult {
        return when (layout.alignment) {
            SidewalkAlignment.ON_ROAD -> EvalResult(
                roadDanger(layout),
                hugAnnounced = true,
            )
            SidewalkAlignment.DRIFTING_TO_ROAD -> EvalResult(
                driftWarning(layout),
                hugAnnounced = true,
            )
            SidewalkAlignment.ON_SIDEWALK -> onSidewalk(
                layout = layout,
                wasOffSidewalk = wasOffSidewalk,
                hugAnnounced = hugAnnounced,
            )
            SidewalkAlignment.UNKNOWN -> EvalResult(
                spatialFallback(corridor),
                hugAnnounced = hugAnnounced,
            )
        }
    }

    private fun roadDanger(layout: StreetLayoutState): Signal {
        val (left, right) = turnTowardSafeBeeps(layout.safeSide, 0.95f)
        return Signal(
            level = Level.ROAD,
            leftBeep = if (layout.safeSide == RoadSide.UNKNOWN) 0.95f else left.coerceAtLeast(0.75f),
            rightBeep = if (layout.safeSide == RoadSide.UNKNOWN) 0f else right.coerceAtLeast(0.75f),
            continuous = true,
            warning = true,
            haptic = Signal.Haptic.ROAD_DANGER,
            inSafeZone = false,
        )
    }

    private fun driftWarning(layout: StreetLayoutState): Signal {
        val intensity = (layout.driftScore * 0.45f + 0.55f).coerceIn(0.60f, 0.95f)
        val (left, right) = turnTowardSafeBeeps(layout.safeSide, intensity)
        val haptic = when (layout.safeSide) {
            RoadSide.LEFT -> Signal.Haptic.NUDGE_LEFT
            RoadSide.RIGHT -> Signal.Haptic.NUDGE_RIGHT
            RoadSide.UNKNOWN -> Signal.Haptic.NONE
        }
        return Signal(
            level = Level.DRIFT,
            leftBeep = left,
            rightBeep = right,
            continuous = true,
            haptic = haptic,
            inSafeZone = false,
        )
    }

    private fun onSidewalk(
        layout: StreetLayoutState,
        wasOffSidewalk: Boolean,
        hugAnnounced: Boolean,
    ): EvalResult {
        if (wasOffSidewalk) {
            return EvalResult(
                Signal(
                    level = Level.OK,
                    haptic = Signal.Haptic.RECOVERED,
                    inSafeZone = true,
                ),
                hugAnnounced = true,
            )
        }

        val inSafeZone = layout.centeringScore >= SAFE_ZONE_CENTER &&
            layout.driftScore < SAFE_ZONE_DRIFT_MAX

        if (inSafeZone) {
            return EvalResult(
                Signal(level = Level.OK, inSafeZone = true),
                hugAnnounced = true,
            )
        }

        val intensity = ((SAFE_ZONE_CENTER - layout.centeringScore) / SAFE_ZONE_CENTER)
            .coerceIn(0.55f, 0.92f)
        val (left, right) = turnTowardSafeBeeps(layout.safeSide, intensity)
        return EvalResult(
            Signal(
                level = Level.OK,
                leftBeep = left,
                rightBeep = right,
                continuous = true,
                inSafeZone = false,
            ),
            hugAnnounced = true,
        )
    }

    /** Sin fachada clara: pitidos espaciales por proximidad L/R. */
    private fun spatialFallback(corridor: CorridorState): Signal {
        val left = corridor.leftProximity
        val right = corridor.rightProximity
        val max = maxOf(left, right)
        if (max < 0.18f) {
            return Signal(level = Level.OK, inSafeZone = true)
        }
        return Signal(
            level = Level.OK,
            leftBeep = left,
            rightBeep = right,
            continuous = max >= 0.40f,
            inSafeZone = false,
        )
    }

    private fun turnTowardSafeBeeps(safeSide: RoadSide, intensity: Float): Pair<Float, Float> {
        val i = intensity.coerceIn(0.50f, 0.98f)
        return when (safeSide) {
            RoadSide.LEFT -> i to 0f
            RoadSide.RIGHT -> 0f to i
            // Un oído (izquierdo) para que el tono continuo suene seguro
            RoadSide.UNKNOWN -> i to 0f
        }
    }

    const val SAFE_ZONE_CENTER = 0.62f
    const val SAFE_ZONE_DRIFT_MAX = 0.20f
}
