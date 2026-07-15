package io.lazaro.pathguide

import kotlin.math.abs

data class DoorwayVoiceCue(
    val message: String,
    val debounceMs: Long,
    val cueId: String,
)

data class DoorwayGuideResult(
    val phase: DoorwayGuidePhase,
    val leftBeep: Float,
    val rightBeep: Float,
    val doorwayMode: Boolean,
    val continuousTone: Boolean,
    val voiceCue: DoorwayVoiceCue? = null,
) {
    val isGuiding: Boolean
        get() = phase != DoorwayGuidePhase.IDLE
}

class DoorwayGuideProtocol {

    private var phase = DoorwayGuidePhase.IDLE
    private var detectionStableMs = 0L
    private var centeredStableMs = 0L
    private var lostMs = 0L
    private var announcedCentered = false
    private var latchedCentered = false

    fun update(corridor: CorridorState, deltaMs: Long): DoorwayGuideResult {
        val door = corridor.doorway
        val active = corridor.doorwayActive && door.confidence >= MIN_CONFIDENCE

        if (!active) {
            lostMs += deltaMs
            if (phase != DoorwayGuidePhase.IDLE && lostMs >= lostResetMs()) {
                reset()
            }
            return idleResult()
        }

        lostMs = 0L
        detectionStableMs += deltaMs

        val approach = door.approachFactor
        val offset = perspectiveAdjustedOffset(door.centerNorm - 0.5f, approach)
        val absOffset = abs(offset)
        val tolerances = tolerancesFor(approach)

        phase = resolvePhase(offset, absOffset, tolerances, approach)

        if (absOffset < tolerances.centeredEnter || door.isCentered) {
            centeredStableMs += deltaMs
        } else if (!latchedCentered) {
            centeredStableMs = 0L
            announcedCentered = false
        }

        if ((phase == DoorwayGuidePhase.CENTERED || door.isCentered) &&
            centeredStableMs >= CENTERED_HOLD_MS
        ) {
            latchedCentered = true
            phase = DoorwayGuidePhase.PASSING
        }

        val beeps = beepLevels(phase, door, tolerances, approach)
        val voiceCue = buildVoiceCue(phase, offset, door, approach, tolerances)

        return DoorwayGuideResult(
            phase = phase,
            leftBeep = beeps.first,
            rightBeep = beeps.second,
            doorwayMode = true,
            continuousTone = beeps.third,
            voiceCue = voiceCue,
        )
    }

    fun reset() {
        phase = DoorwayGuidePhase.IDLE
        detectionStableMs = 0L
        centeredStableMs = 0L
        lostMs = 0L
        announcedCentered = false
        latchedCentered = false
    }

    fun currentPhase(): DoorwayGuidePhase = phase

    private data class Tolerances(
        val turn: Float,
        val align: Float,
        val centeredEnter: Float,
        val centeredExit: Float,
        val drift: Float,
    )

    private fun tolerancesFor(approach: Float): Tolerances {
        val scale = 1f + approach * 1.35f
        return Tolerances(
            turn = TURN_OFFSET * scale,
            align = ALIGN_OFFSET * scale,
            centeredEnter = CENTERED_ENTER * scale,
            centeredExit = CENTERED_EXIT * scale,
            drift = DRIFT_PROXIMITY + approach * 0.22f,
        )
    }

    private fun perspectiveAdjustedOffset(rawOffset: Float, approach: Float): Float {
        val damp = 1f - approach * 0.82f
        return rawOffset * damp
    }

    private fun resolvePhase(
        offset: Float,
        absOffset: Float,
        tolerances: Tolerances,
        approach: Float,
    ): DoorwayGuidePhase {
        if (detectionStableMs < APPROACH_STABLE_MS) {
            return DoorwayGuidePhase.APPROACHING
        }

        if (latchedCentered || phase == DoorwayGuidePhase.PASSING) {
            if (absOffset < tolerances.centeredExit && approach > 0.45f) {
                return DoorwayGuidePhase.PASSING
            }
            if (absOffset >= tolerances.turn && approach < 0.55f) {
                latchedCentered = false
            } else if (latchedCentered) {
                return DoorwayGuidePhase.PASSING
            }
        }

        return when (phase) {
            DoorwayGuidePhase.CENTERED -> when {
                absOffset >= tolerances.centeredExit -> resolveTurnOrAlign(offset, absOffset, tolerances)
                else -> DoorwayGuidePhase.CENTERED
            }

            DoorwayGuidePhase.PASSING -> when {
                latchedCentered -> DoorwayGuidePhase.PASSING
                absOffset >= tolerances.turn -> resolveTurnOrAlign(offset, absOffset, tolerances)
                else -> DoorwayGuidePhase.PASSING
            }

            else -> when {
                absOffset < tolerances.centeredEnter -> DoorwayGuidePhase.CENTERED
                absOffset < tolerances.align -> DoorwayGuidePhase.ALIGNING
                else -> resolveTurnOrAlign(offset, absOffset, tolerances)
            }
        }
    }

    private fun resolveTurnOrAlign(
        offset: Float,
        absOffset: Float,
        tolerances: Tolerances,
    ): DoorwayGuidePhase {
        return if (absOffset >= tolerances.align) {
            if (offset < 0f) DoorwayGuidePhase.TURN_LEFT else DoorwayGuidePhase.TURN_RIGHT
        } else {
            DoorwayGuidePhase.ALIGNING
        }
    }

    private fun beepLevels(
        phase: DoorwayGuidePhase,
        door: DoorwayState,
        tolerances: Tolerances,
        approach: Float,
    ): Triple<Float, Float, Boolean> {
        if (phase == DoorwayGuidePhase.APPROACHING) {
            return Triple(0f, 0f, false)
        }

        if (latchedCentered || phase == DoorwayGuidePhase.CENTERED) {
            return Triple(0f, 0f, false)
        }

        if (phase == DoorwayGuidePhase.PASSING) {
            val severeLeft = door.leftProximity >= tolerances.drift + 0.12f &&
                door.leftProximity > door.rightProximity + 0.18f
            val severeRight = door.rightProximity >= tolerances.drift + 0.12f &&
                door.rightProximity > door.leftProximity + 0.18f
            if (!severeLeft && !severeRight) {
                return Triple(0f, 0f, false)
            }
        }

        val damp = (1f - approach * 0.65f).coerceAtLeast(0.25f)
        val left = door.leftProximity * damp
        val right = door.rightProximity * damp
        val continuous = phase in CONTINUOUS_PHASES

        return when (phase) {
            DoorwayGuidePhase.TURN_LEFT -> Triple(left, 0f, continuous)
            DoorwayGuidePhase.TURN_RIGHT -> Triple(0f, right, continuous)
            DoorwayGuidePhase.ALIGNING -> when {
                left > right + 0.12f -> Triple(left, 0f, continuous)
                right > left + 0.12f -> Triple(0f, right, continuous)
                else -> Triple(0f, 0f, false)
            }
            else -> Triple(left, right, continuous)
        }
    }

    private fun buildVoiceCue(
        phase: DoorwayGuidePhase,
        offset: Float,
        door: DoorwayState,
        approach: Float,
        tolerances: Tolerances,
    ): DoorwayVoiceCue? {
        if (latchedCentered || approach > 0.55f) {
            return when (phase) {
                DoorwayGuidePhase.CENTERED -> centeredCue(door)
                else -> null
            }
        }

        return when (phase) {
            DoorwayGuidePhase.APPROACHING -> {
                if (detectionStableMs >= APPROACH_STABLE_MS) {
                    DoorwayVoiceCue(
                        message = SpatialPhraseBuilder.doorwayApproachPhrase(door),
                        debounceMs = APPROACH_DEBOUNCE_MS,
                        cueId = "approach",
                    )
                } else {
                    null
                }
            }

            DoorwayGuidePhase.TURN_LEFT -> {
                if (approach < 0.50f) {
                    DoorwayVoiceCue(
                        message = SpatialPhraseBuilder.doorwayTurnPhrase(offset, turnLeft = true),
                        debounceMs = TURN_DEBOUNCE_MS,
                        cueId = "turn_left",
                    )
                } else {
                    null
                }
            }

            DoorwayGuidePhase.TURN_RIGHT -> {
                if (approach < 0.50f) {
                    DoorwayVoiceCue(
                        message = SpatialPhraseBuilder.doorwayTurnPhrase(offset, turnLeft = false),
                        debounceMs = TURN_DEBOUNCE_MS,
                        cueId = "turn_right",
                    )
                } else {
                    null
                }
            }

            DoorwayGuidePhase.ALIGNING -> {
                if (approach > 0.40f) return centeredCue(door)
                when {
                    offset < -tolerances.align -> DoorwayVoiceCue(
                        message = SpatialPhraseBuilder.doorwayAlignPhrase(offset, turnLeft = true),
                        debounceMs = ALIGN_DEBOUNCE_MS,
                        cueId = "align_left",
                    )

                    offset > tolerances.align -> DoorwayVoiceCue(
                        message = SpatialPhraseBuilder.doorwayAlignPhrase(offset, turnLeft = false),
                        debounceMs = ALIGN_DEBOUNCE_MS,
                        cueId = "align_right",
                    )

                    else -> centeredCue(door)
                }
            }

            DoorwayGuidePhase.CENTERED -> centeredCue(door)

            DoorwayGuidePhase.PASSING -> null

            DoorwayGuidePhase.IDLE -> null
        }
    }

    private fun centeredCue(door: DoorwayState): DoorwayVoiceCue? {
        if (announcedCentered) return null
        announcedCentered = true
        return DoorwayVoiceCue(
            message = SpatialPhraseBuilder.doorwayCenteredPhrase(door),
            debounceMs = CENTERED_DEBOUNCE_MS,
            cueId = "centered",
        )
    }

    private fun lostResetMs(): Long {
        return if (latchedCentered) 700L else LOST_RESET_MS
    }

    private fun idleResult(): DoorwayGuideResult {
        return DoorwayGuideResult(
            phase = DoorwayGuidePhase.IDLE,
            leftBeep = 0f,
            rightBeep = 0f,
            doorwayMode = false,
            continuousTone = false,
        )
    }

    companion object {
        private val CONTINUOUS_PHASES = setOf(
            DoorwayGuidePhase.TURN_LEFT,
            DoorwayGuidePhase.TURN_RIGHT,
            DoorwayGuidePhase.ALIGNING,
        )

        private const val MIN_CONFIDENCE = 0.60f
        private const val APPROACH_STABLE_MS = 700L
        private const val CENTERED_HOLD_MS = 400L
        private const val LOST_RESET_MS = 1_500L

        private const val TURN_OFFSET = 0.13f
        private const val ALIGN_OFFSET = 0.09f
        private const val CENTERED_ENTER = 0.09f
        private const val CENTERED_EXIT = 0.16f
        private const val DRIFT_PROXIMITY = 0.52f

        private const val APPROACH_DEBOUNCE_MS = 22_000L
        private const val TURN_DEBOUNCE_MS = 9_000L
        private const val ALIGN_DEBOUNCE_MS = 12_000L
        private const val CENTERED_DEBOUNCE_MS = 30_000L
    }
}
