package io.lazaro.pathguide

import io.lazaro.navigation.NavigationAudioCoordinator
import io.lazaro.navigation.TurnSide
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class ExitPriorityBrain @Inject constructor(
    private val exitGuideAnnouncer: ExitGuideAnnouncer,
    private val turnAlignmentGuide: TurnAlignmentGuide,
    private val navigationAudioCoordinator: NavigationAudioCoordinator,
) {
    private val junctionDetector = JunctionDetector()
    private val approachEstimator = ApproachEstimator()
    private val doorwayGuideProtocol = DoorwayGuideProtocol()

    private var phase = ExitBrainPhase.EXPLORE
    private var exitTarget = ExitTarget()
    private var exitLostMs = 0L
    private var exitStableMs = 0L
    private var announcedExitFound = false
    private var frontalStableMs = 0L

    fun reset() {
        phase = ExitBrainPhase.EXPLORE
        exitTarget = ExitTarget()
        exitLostMs = 0L
        exitStableMs = 0L
        announcedExitFound = false
        frontalStableMs = 0L
        junctionDetector.reset()
        approachEstimator.reset()
        doorwayGuideProtocol.reset()
        exitGuideAnnouncer.reset()
        turnAlignmentGuide.reset()
    }

    fun update(
        corridor: CorridorState,
        deltaMs: Long,
        config: PathGuideConfig,
        mode: PathGuideMode,
        now: Long = System.currentTimeMillis(),
        obstacleLabel: String? = null,
    ): ExitBrainFrameResult {
        val junction = junctionDetector.detect(corridor)
        val opening = if (corridor.doorwayActive) {
            OpeningCandidate(
                type = classifyOpening(corridor.doorway),
                detected = corridor.doorway.detected,
                confidence = corridor.doorway.confidence,
                leftJambNorm = corridor.doorway.leftJambNorm,
                rightJambNorm = corridor.doorway.rightJambNorm,
                centerNorm = corridor.doorway.centerNorm,
                openingWidthNorm = corridor.doorway.openingWidthNorm,
                approachFactor = corridor.doorway.approachFactor,
                leftProximity = corridor.doorway.leftProximity,
                rightProximity = corridor.doorway.rightProximity,
                isCentered = corridor.doorway.isCentered,
            )
        } else {
            null
        }

        val approach = approachEstimator.update(
            openingWidthNorm = opening?.openingWidthNorm,
            frontalSeverity = corridor.frontalSeverity,
            frontalCloseRange = corridor.frontalCloseRange,
            deltaMs = deltaMs,
        )

        val resolvedTarget = resolveExitTarget(opening, junction, corridor)

        val doorwayGuide = if (
            config.doorwayAlertsEnabled &&
            corridor.doorwayActive &&
            corridor.doorway.confidence >= MIN_OPENING_CONFIDENCE
        ) {
            doorwayGuideProtocol.update(corridor, deltaMs)
        } else {
            if (!corridor.doorwayActive) {
                doorwayGuideProtocol.reset()
            }
            DoorwayGuideResult(
                phase = DoorwayGuidePhase.IDLE,
                leftBeep = 0f,
                rightBeep = 0f,
                doorwayMode = false,
                continuousTone = false,
            )
        }

        updatePhase(resolvedTarget, corridor, doorwayGuide, deltaMs)
        exitTarget = resolvedTarget

        val doorwayAlignment = if (doorwayGuide.isGuiding) {
            turnAlignmentGuide.updateDoorway(
                visualTargetDeg = VisualTurnEstimator.doorwayOffsetDegrees(corridor.doorway),
                phase = doorwayGuide.phase,
            )
        } else {
            null
        }

        val navAlignment = if (mode == PathGuideMode.NAVEGACION) {
            turnAlignmentGuide.updateNavigation(
                side = navigationAudioCoordinator.lastTurnSide(),
                withinTurnWindow = navigationAudioCoordinator.isWithinTurnWindow(now),
                visualCorridorDeg = VisualTurnEstimator.corridorOffsetDegrees(corridor),
            )
        } else {
            null
        }

        val alignment = doorwayAlignment ?: navAlignment
        val imuCue = alignment?.voiceCue

        val exitCue = if (!announcedExitFound && phase == ExitBrainPhase.EXIT_FOUND) {
            announcedExitFound = true
            exitGuideAnnouncer.buildExitFoundCue(resolvedTarget, opening, corridor)
        } else if (phase == ExitBrainPhase.EXPLORE && junction != JunctionType.NONE) {
            exitGuideAnnouncer.buildJunctionCue(junction, corridor)
        } else {
            null
        }

        val approachCue = if (phase == ExitBrainPhase.BLOCKED && approach.announceReady) {
            exitGuideAnnouncer.buildApproachCue(
                label = "obstáculo",
                approach = approach,
                corridor = corridor,
            )
        } else {
            null
        }

        val voiceCue = exitGuideAnnouncer.mergeVoiceCue(
            exitCue = exitCue,
            doorwayCue = doorwayGuide.voiceCue,
            imuCue = imuCue,
            approachCue = approachCue,
            phase = phase,
        )

        val blocksExit = blocksExitPath(corridor, resolvedTarget)
        val shouldAnnounceFrontal = config.frontalAlertsEnabled &&
            corridor.isFrontallyBlocked &&
            blocksExit &&
            frontalStableMs >= FRONTAL_STABLE_MS &&
            (corridor.frontalSeverity >= 0.28f || corridor.centerProximity >= 0.30f)

        val beeps = resolveBeeps(
            corridor = corridor,
            doorwayGuide = doorwayGuide,
            alignment = alignment,
            mode = mode,
            config = config,
        )

        return ExitBrainFrameResult(
            phase = phase,
            leftBeep = beeps.left,
            rightBeep = beeps.right,
            doorwayMode = beeps.doorwayMode,
            continuousTone = beeps.continuousTone,
            voiceCue = voiceCue,
            isExitGuiding = doorwayGuide.isGuiding || phase in GUIDING_PHASES ||
                (alignment != null && !alignment.aligned &&
                    (alignment.guideLeftBeep > 0.1f || alignment.guideRightBeep > 0.1f)),
            junctionType = junction,
            approachState = approach,
            shouldAnnounceFrontal = shouldAnnounceFrontal,
            frontalBlocksExit = blocksExit,
            doorwayPhase = doorwayGuide.phase,
            turnRemainingDeg = alignment?.remainingDeg,
            turnTurnedDeg = alignment?.turnedDeg,
            suppressScene = true,
            exitTarget = resolvedTarget,
            justAligned = alignment?.justAligned == true,
        )
    }

    fun onFrontalAnnounced() {
        frontalStableMs = 0L
    }

    fun trackFrontalBlocked(blocked: Boolean, deltaMs: Long) {
        if (blocked) {
            frontalStableMs += deltaMs
        } else {
            frontalStableMs = 0L
        }
    }

    private fun updatePhase(
        target: ExitTarget,
        corridor: CorridorState,
        doorwayGuide: DoorwayGuideResult,
        deltaMs: Long,
    ) {
        val hasExit = target.side != ExitSide.UNKNOWN ||
            target.junction != JunctionType.NONE ||
            (corridor.doorwayActive && corridor.doorway.confidence >= MIN_OPENING_CONFIDENCE)

        if (!hasExit) {
            exitLostMs += deltaMs
            if (exitLostMs >= EXIT_LOST_MS) {
                phase = ExitBrainPhase.EXPLORE
                announcedExitFound = false
                exitStableMs = 0L
            }
            return
        }

        exitLostMs = 0L
        exitStableMs += deltaMs

        if (blocksExitPath(corridor, target)) {
            phase = ExitBrainPhase.BLOCKED
            return
        }

        if (phase == ExitBrainPhase.BLOCKED) {
            phase = ExitBrainPhase.EXPLORE
            announcedExitFound = false
            exitStableMs = 0L
        }

        if (phase == ExitBrainPhase.EXPLORE && exitStableMs >= EXIT_STABLE_MS) {
            phase = ExitBrainPhase.EXIT_FOUND
            return
        }

        if (phase == ExitBrainPhase.EXIT_FOUND || phase == ExitBrainPhase.ALIGN ||
            phase == ExitBrainPhase.CENTERED || phase == ExitBrainPhase.PASS
        ) {
            phase = when (doorwayGuide.phase) {
                DoorwayGuidePhase.TURN_LEFT, DoorwayGuidePhase.TURN_RIGHT -> ExitBrainPhase.ALIGN
                DoorwayGuidePhase.ALIGNING -> ExitBrainPhase.ALIGN
                DoorwayGuidePhase.CENTERED -> ExitBrainPhase.CENTERED
                DoorwayGuidePhase.PASSING -> ExitBrainPhase.PASS
                DoorwayGuidePhase.APPROACHING -> ExitBrainPhase.EXIT_FOUND
                else -> phase
            }
        }
    }

    private fun resolveExitTarget(
        opening: OpeningCandidate?,
        junction: JunctionType,
        corridor: CorridorState,
    ): ExitTarget {
        if (opening?.detected == true && opening.confidence >= MIN_OPENING_CONFIDENCE) {
            val offset = opening.centerNorm - 0.5f
            val side = when {
                abs(offset) < 0.10f -> ExitSide.CENTER
                offset < 0f -> ExitSide.LEFT
                else -> ExitSide.RIGHT
            }
            return ExitTarget(side = side, opening = opening, junction = junction)
        }

        return when (junction) {
            JunctionType.T_LEFT -> ExitTarget(side = ExitSide.LEFT, junction = junction)
            JunctionType.T_RIGHT -> ExitTarget(side = ExitSide.RIGHT, junction = junction)
            JunctionType.T_BOTH -> ExitTarget(
                side = if (corridor.leftProximity < corridor.rightProximity) {
                    ExitSide.LEFT
                } else {
                    ExitSide.RIGHT
                },
                junction = junction,
            )
            JunctionType.DEAD_END -> ExitTarget(side = ExitSide.UNKNOWN, junction = junction)
            JunctionType.NONE -> ExitTarget(junction = junction)
        }
    }

    private fun blocksExitPath(corridor: CorridorState, target: ExitTarget): Boolean {
        if (!corridor.isFrontallyBlocked) return false
        return when (target.side) {
            ExitSide.CENTER -> true
            ExitSide.LEFT -> corridor.centerProximity >= 0.26f || corridor.leftProximity >= 0.34f
            ExitSide.RIGHT -> corridor.centerProximity >= 0.26f || corridor.rightProximity >= 0.34f
            ExitSide.UNKNOWN -> corridor.centerProximity >= 0.30f
        }
    }

    private fun resolveBeeps(
        corridor: CorridorState,
        doorwayGuide: DoorwayGuideResult,
        alignment: TurnAlignmentState?,
        mode: PathGuideMode,
        config: PathGuideConfig,
    ): BeepOutput {
        // Prioridad 1: guía IMU — pitido en el lado hacia el que girar.
        if (alignment != null && !alignment.aligned &&
            (alignment.guideLeftBeep > 0.05f || alignment.guideRightBeep > 0.05f)
        ) {
            return BeepOutput(
                left = alignment.guideLeftBeep,
                right = alignment.guideRightBeep,
                doorwayMode = true,
                continuousTone = alignment.continuousGuide,
            )
        }

        if (doorwayGuide.isGuiding && config.doorwayAlertsEnabled) {
            val continuous = doorwayGuide.continuousTone && alignment?.aligned != true
            return BeepOutput(
                left = doorwayGuide.leftBeep,
                right = doorwayGuide.rightBeep,
                doorwayMode = true,
                continuousTone = continuous,
            )
        }

        var left = corridor.leftProximity
        var right = corridor.rightProximity
        val biased = applyNavigationTurnBias(left, right, mode)
        left = biased.first
        right = biased.second

        if (alignment?.aligned == true) {
            return BeepOutput(left * 0.35f, right * 0.35f, doorwayMode = corridor.doorwayActive)
        }

        return BeepOutput(left, right, doorwayMode = corridor.doorwayActive)
    }

    private fun applyNavigationTurnBias(
        left: Float,
        right: Float,
        mode: PathGuideMode,
    ): Pair<Float, Float> {
        if (mode != PathGuideMode.NAVEGACION) return left to right
        return when (navigationAudioCoordinator.lastTurnSide()) {
            TurnSide.LEFT -> left to right * 0.65f
            TurnSide.RIGHT -> left * 0.65f to right
            TurnSide.U_TURN -> left * 0.8f to right * 0.8f
            null -> left to right
        }
    }

    private fun classifyOpening(doorway: DoorwayState): OpeningType {
        return when {
            doorway.openingWidthNorm >= 0.48f -> OpeningType.ARCH
            doorway.openingWidthNorm >= 0.40f && doorway.centerNorm in 0.38f..0.62f ->
                OpeningType.CORRIDOR_END
            else -> OpeningType.DOOR
        }
    }

    private data class BeepOutput(
        val left: Float,
        val right: Float,
        val doorwayMode: Boolean,
        val continuousTone: Boolean = false,
    )

    companion object {
        private val GUIDING_PHASES = setOf(
            ExitBrainPhase.EXIT_FOUND,
            ExitBrainPhase.ALIGN,
            ExitBrainPhase.CENTERED,
            ExitBrainPhase.PASS,
        )

        private const val MIN_OPENING_CONFIDENCE = 0.58f
        private const val EXIT_STABLE_MS = 450L
        private const val EXIT_LOST_MS = 1_200L
        private const val FRONTAL_STABLE_MS = 700L
    }
}
