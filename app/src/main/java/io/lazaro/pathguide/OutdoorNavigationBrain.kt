package io.lazaro.pathguide

import io.lazaro.navigation.MapsVisionFusionCoordinator
import io.lazaro.navigation.NavigationAudioCoordinator
import io.lazaro.navigation.TurnSide
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

@Singleton
class OutdoorNavigationBrain @Inject constructor(
    private val navigationAudioCoordinator: NavigationAudioCoordinator,
    private val mapsVisionFusionCoordinator: MapsVisionFusionCoordinator,
    private val turnAlignmentGuide: TurnAlignmentGuide,
) {
    private val streetLayoutDetector = StreetLayoutDetector()
    private val crosswalkDetector = CrosswalkDetector()
    private val junctionDetector = OutdoorJunctionDetector()
    private val beepStabilizer = BeepSignalStabilizer()

    private var lastSidewalkCueMs = 0L
    private var lastDriftCueMs = 0L
    private var lastCrossSearchCueMs = 0L
    private var lastCrosswalkCueMs = 0L
    private var lastJunctionCueMs = 0L
    private var warningFrontalMs = 0L

    fun reset() {
        streetLayoutDetector.reset()
        crosswalkDetector.reset()
        junctionDetector.reset()
        turnAlignmentGuide.reset()
        lastSidewalkCueMs = 0L
        lastDriftCueMs = 0L
        lastCrossSearchCueMs = 0L
        lastCrosswalkCueMs = 0L
        lastJunctionCueMs = 0L
        warningFrontalMs = 0L
    }

    fun update(
        gray: ByteArray,
        width: Int,
        height: Int,
        corridor: CorridorState,
        deltaMs: Long,
        config: PathGuideConfig,
        now: Long = System.currentTimeMillis(),
    ): ExitBrainFrameResult {
        val streetLayout = streetLayoutDetector.update(corridor)
        val crosswalk = if (
            mapsVisionFusionCoordinator.isCrossingSearchActive(now) ||
            mapsVisionFusionCoordinator.currentPhase() == OutdoorNavPhase.CROSSING ||
            mapsVisionFusionCoordinator.currentPhase() == OutdoorNavPhase.APPROACH_CROSSING
        ) {
            crosswalkDetector.detect(gray, width, height)
        } else {
            CrosswalkState()
        }

        val junction = junctionDetector.detect(corridor)
        val phase = mapsVisionFusionCoordinator.update(streetLayout, crosswalk, junction, now)

        val navAlignment = turnAlignmentGuide.updateNavigation(
            side = navigationAudioCoordinator.lastTurnSide(),
            withinTurnWindow = navigationAudioCoordinator.isWithinTurnWindow(now),
            visualCorridorDeg = VisualTurnEstimator.corridorOffsetDegrees(corridor),
        )

        val voiceCue = buildVoiceCue(
            phase = phase,
            streetLayout = streetLayout,
            crosswalk = crosswalk,
            junction = junction,
            corridor = corridor,
            imuCue = navAlignment?.voiceCue,
            now = now,
        )

        val (leftBeep, rightBeep, warningMode) = buildBeeps(
            streetLayout = streetLayout,
            corridor = corridor,
            phase = phase,
            config = config,
            now = now,
        )

        val shouldAnnounceFrontal = config.frontalAlertsEnabled &&
            corridor.isFrontallyBlocked &&
            corridor.frontalSeverity >= 0.42f &&
            phase != OutdoorNavPhase.CROSSING

        return ExitBrainFrameResult(
            phase = when (phase) {
                OutdoorNavPhase.DRIFT_WARNING -> ExitBrainPhase.BLOCKED
                OutdoorNavPhase.TURN_AT_JUNCTION -> ExitBrainPhase.ALIGN
                else -> ExitBrainPhase.EXPLORE
            },
            leftBeep = leftBeep,
            rightBeep = rightBeep,
            doorwayMode = false,
            continuousTone = warningMode,
            voiceCue = voiceCue,
            isExitGuiding = phase == OutdoorNavPhase.TURN_AT_JUNCTION,
            junctionType = junction,
            shouldAnnounceFrontal = shouldAnnounceFrontal,
            frontalBlocksExit = shouldAnnounceFrontal,
            turnRemainingDeg = navAlignment?.remainingDeg,
            turnTurnedDeg = navAlignment?.turnedDeg,
            suppressScene = phase == OutdoorNavPhase.CROSSING ||
                navigationAudioCoordinator.lastInstructionType() == MapsInstructionType.CROSS_STREET,
            outdoorPhase = phase,
            roadSide = streetLayout.roadSide,
            safeSide = streetLayout.safeSide,
            sidewalkAlignment = streetLayout.alignment,
            mapsInstructionType = navigationAudioCoordinator.lastInstructionType(),
            warningMode = warningMode,
        )
    }

    private fun buildVoiceCue(
        phase: OutdoorNavPhase,
        streetLayout: StreetLayoutState,
        crosswalk: CrosswalkState,
        junction: JunctionType,
        corridor: CorridorState,
        imuCue: DoorwayVoiceCue?,
        now: Long,
    ): DoorwayVoiceCue? {
        if (imuCue != null) return imuCue

        when (phase) {
            OutdoorNavPhase.DRIFT_WARNING -> {
                if (now - lastDriftCueMs < DRIFT_DEBOUNCE_MS) return null
                val message = OutdoorPhraseBuilder.driftWarningPhrase(streetLayout) ?: return null
                lastDriftCueMs = now
                return DoorwayVoiceCue(message, debounceMs = DRIFT_DEBOUNCE_MS, cueId = "outdoor_drift")
            }
            OutdoorNavPhase.APPROACH_CROSSING -> {
                if (crosswalk.detected) {
                    if (now - lastCrosswalkCueMs < CROSSWALK_DEBOUNCE_MS) return null
                    val message = OutdoorPhraseBuilder.crosswalkFoundPhrase(crosswalk) ?: return null
                    lastCrosswalkCueMs = now
                    return DoorwayVoiceCue(message, debounceMs = CROSSWALK_DEBOUNCE_MS, cueId = "outdoor_crosswalk")
                }
                if (now - lastCrossSearchCueMs < CROSS_SEARCH_DEBOUNCE_MS) return null
                lastCrossSearchCueMs = now
                return DoorwayVoiceCue(
                    OutdoorPhraseBuilder.crossSearchPhrase(),
                    debounceMs = CROSS_SEARCH_DEBOUNCE_MS,
                    cueId = "outdoor_cross_search",
                )
            }
            OutdoorNavPhase.CROSSING -> {
                if (!crosswalk.detected) return null
                if (now - lastCrosswalkCueMs < CROSSWALK_DEBOUNCE_MS) return null
                val message = OutdoorPhraseBuilder.crosswalkFoundPhrase(crosswalk) ?: return null
                lastCrosswalkCueMs = now
                return DoorwayVoiceCue(message, debounceMs = CROSSWALK_DEBOUNCE_MS, cueId = "outdoor_crossing")
            }
            OutdoorNavPhase.TURN_AT_JUNCTION -> {
                if (junction == JunctionType.NONE) return null
                if (now - lastJunctionCueMs < JUNCTION_DEBOUNCE_MS) return null
                val message = OutdoorPhraseBuilder.junctionTurnPhrase(
                    junction = junction,
                    turnSide = navigationAudioCoordinator.lastTurnSide(),
                    layout = streetLayout,
                    corridor = corridor,
                ) ?: return null
                lastJunctionCueMs = now
                return DoorwayVoiceCue(message, debounceMs = JUNCTION_DEBOUNCE_MS, cueId = "outdoor_junction")
            }
            OutdoorNavPhase.ARRIVING -> {
                return DoorwayVoiceCue(
                    OutdoorPhraseBuilder.arrivalPhrase(),
                    debounceMs = ARRIVE_DEBOUNCE_MS,
                    cueId = "outdoor_arrive",
                )
            }
            OutdoorNavPhase.FOLLOW_SIDEWALK -> {
                if (streetLayout.roadSide == RoadSide.UNKNOWN) return null
                if (now - lastSidewalkCueMs < SIDEWALK_DEBOUNCE_MS) return null
                val message = OutdoorPhraseBuilder.sidewalkGuidancePhrase(streetLayout) ?: return null
                lastSidewalkCueMs = now
                return DoorwayVoiceCue(message, debounceMs = SIDEWALK_DEBOUNCE_MS, cueId = "outdoor_sidewalk")
            }
            else -> return null
        }
    }

    private fun buildBeeps(
        streetLayout: StreetLayoutState,
        corridor: CorridorState,
        phase: OutdoorNavPhase,
        config: PathGuideConfig,
        now: Long,
    ): Triple<Float, Float, Boolean> {
        if (!config.doorwayAlertsEnabled) return Triple(0f, 0f, false)

        if (phase == OutdoorNavPhase.DRIFT_WARNING || streetLayout.alignment == SidewalkAlignment.ON_ROAD) {
            warningFrontalMs = now
            return Triple(0.85f, 0.85f, true)
        }

        var left = 0f
        var right = 0f

        when (streetLayout.safeSide) {
            RoadSide.LEFT -> left = max(corridor.leftProximity, 0.35f)
            RoadSide.RIGHT -> right = max(corridor.rightProximity, 0.35f)
            RoadSide.UNKNOWN -> {
                left = corridor.leftProximity
                right = corridor.rightProximity
            }
        }

        val (stableLeft, stableRight) = beepStabilizer.stabilize(left, right, doorwayMode = false)
        return Triple(stableLeft, stableRight, false)
    }

    companion object {
        private const val SIDEWALK_DEBOUNCE_MS = 28_000L
        private const val DRIFT_DEBOUNCE_MS = 8_000L
        private const val CROSS_SEARCH_DEBOUNCE_MS = 12_000L
        private const val CROSSWALK_DEBOUNCE_MS = 10_000L
        private const val JUNCTION_DEBOUNCE_MS = 12_000L
        private const val ARRIVE_DEBOUNCE_MS = 20_000L
    }
}

class OutdoorJunctionDetector {
    private val detector = JunctionDetector()

    fun detect(corridor: CorridorState): JunctionType = detector.detect(corridor)

    fun reset() = detector.reset()
}
