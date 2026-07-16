package io.lazaro.pathguide

import android.content.Context
import androidx.camera.core.ImageProxy
import dagger.hilt.android.qualifiers.ApplicationContext
import io.lazaro.navigation.MapsVisionFusionCoordinator
import io.lazaro.navigation.NavigationAudioCoordinator
import io.lazaro.navigation.TurnHapticFeedback
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cerebro de navegación en **calle**: acera, no calzada, giros Maps + IMU.
 */
@Singleton
class OutdoorNavigationBrain @Inject constructor(
    @ApplicationContext private val context: Context,
    private val navigationAudioCoordinator: NavigationAudioCoordinator,
    private val mapsVisionFusionCoordinator: MapsVisionFusionCoordinator,
    private val turnAlignmentGuide: TurnAlignmentGuide,
    private val depthPerceptionProvider: DepthPerceptionProvider,
) {
    private val streetLayoutDetector = StreetLayoutDetector()
    private val walkableCorridorEstimator = WalkableCorridorEstimator()
    private val lateralGuidanceController = LateralGuidanceController()
    private val doorwayGuideProtocol = DoorwayGuideProtocol()
    private val crosswalkDetector = CrosswalkDetector()
    private val junctionDetector = JunctionDetector()

    private var lastSidewalkVoiceMs = 0L
    private var lastRecoveredMs = 0L
    private var wasOffSidewalk = false
    private var hugAnnounced = false
    private var lastHapticMs = 0L
    private var lastCrossSearchCueMs = 0L
    private var lastCrosswalkCueMs = 0L
    private var lastJunctionCueMs = 0L
    private var lastWalkable = WalkableCorridor()
    private var depthCapabilities: DepthHardwareCapabilities? = null
    private var depthEnabled = false
    private var depthStarted = false

    fun reset() {
        streetLayoutDetector.reset()
        walkableCorridorEstimator.reset()
        doorwayGuideProtocol.reset()
        crosswalkDetector.reset()
        junctionDetector.reset()
        turnAlignmentGuide.reset()
        depthPerceptionProvider.stop()
        depthCapabilities = null
        depthEnabled = false
        depthStarted = false
        lastSidewalkVoiceMs = 0L
        lastRecoveredMs = 0L
        wasOffSidewalk = false
        hugAnnounced = false
        lastHapticMs = 0L
        lastCrossSearchCueMs = 0L
        lastCrosswalkCueMs = 0L
        lastJunctionCueMs = 0L
        lastWalkable = WalkableCorridor()
    }

    fun configureDepth(capabilities: DepthHardwareCapabilities, enabled: Boolean) {
        depthCapabilities = capabilities
        depthPerceptionProvider.configure(capabilities)
        depthEnabled = enabled && capabilities.mode != DepthGuidanceMode.MONOCULAR
        if (depthEnabled && !depthStarted) {
            depthPerceptionProvider.start()
            depthStarted = true
        } else if (!depthEnabled) {
            depthPerceptionProvider.stop()
            depthStarted = false
        }
    }

    /** @deprecated Usar [configureDepth]. */
    fun setDepthEnabled(enabled: Boolean) {
        val caps = depthCapabilities ?: DepthHardwareCapabilities(
            mode = if (enabled) DepthGuidanceMode.LDAF_ONLY else DepthGuidanceMode.MONOCULAR,
            deviceLabel = "desconocido",
            arCoreSupported = false,
            arCoreDepthSupported = false,
            ldafLikely = enabled,
            reason = "legacy",
        )
        configureDepth(caps, enabled)
    }

    fun update(
        gray: ByteArray,
        width: Int,
        height: Int,
        corridor: CorridorState,
        deltaMs: Long,
        config: PathGuideConfig,
        image: ImageProxy? = null,
        now: Long = System.currentTimeMillis(),
    ): ExitBrainFrameResult {
        if (depthEnabled && !depthStarted) {
            depthPerceptionProvider.start()
            depthStarted = true
        }
        val depthSnapshot = if (depthEnabled) {
            depthPerceptionProvider.update(image)
        } else {
            DepthSnapshot()
        }

        val streetLayout = streetLayoutDetector.update(corridor)
        lastWalkable = walkableCorridorEstimator.estimate(
            gray = gray,
            width = width,
            height = height,
            layout = streetLayout,
            depth = depthSnapshot,
        )

        navigationAudioCoordinator.updateCameraContext(
            layout = streetLayout,
            frontalBlocked = corridor.isFrontallyBlocked,
        )

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

        val sidewalkEval = SidewalkNotificationSystem.evaluate(
            layout = streetLayout,
            corridor = corridor,
            now = now,
            lastVoiceMs = lastSidewalkVoiceMs,
            lastRecoveredMs = lastRecoveredMs,
            wasOffSidewalk = wasOffSidewalk,
            hugAnnounced = hugAnnounced,
        )
        val sidewalk = sidewalkEval.signal
        hugAnnounced = sidewalkEval.hugAnnounced

        if (streetLayout.alignment == SidewalkAlignment.ON_ROAD ||
            streetLayout.alignment == SidewalkAlignment.DRIFTING_TO_ROAD
        ) {
            wasOffSidewalk = true
        }
        if (sidewalk.haptic == SidewalkNotificationSystem.Signal.Haptic.RECOVERED ||
            (wasOffSidewalk && sidewalk.inSafeZone &&
                sidewalk.level == SidewalkNotificationSystem.Level.OK)
        ) {
            wasOffSidewalk = false
            lastRecoveredMs = now
        }
        sidewalk.voiceCue?.let { lastSidewalkVoiceMs = now }

        maybeHaptic(sidewalk, now)

        var lateral = lateralGuidanceController.compute(
            walkable = lastWalkable,
            layout = streetLayout,
            corridor = corridor,
            dangerLevel = sidewalk.level,
        )
        lateral = lateralGuidanceController.frontalBoost(
            signal = lateral,
            frontalDistanceM = lastWalkable.frontalDistanceM ?: depthSnapshot.frontalDistanceM,
            frontalSeverity = corridor.frontalSeverity,
        )

        val doorwayGuide = if (
            config.doorwayAlertsEnabled &&
            corridor.doorwayActive &&
            corridor.doorway.confidence >= DOORWAY_MIN_CONFIDENCE
        ) {
            doorwayGuideProtocol.update(corridor, deltaMs)
        } else {
            if (!corridor.doorwayActive) doorwayGuideProtocol.reset()
            DoorwayGuideResult(
                phase = DoorwayGuidePhase.IDLE,
                leftBeep = 0f,
                rightBeep = 0f,
                doorwayMode = false,
                continuousTone = false,
            )
        }

        val junctionBeeps = if (
            junction != JunctionType.NONE &&
            phase != OutdoorNavPhase.CROSSING &&
            sidewalk.level == SidewalkNotificationSystem.Level.OK
        ) {
            JunctionBeepGuide.compute(
                junction = junction,
                corridor = corridor,
                mapsTurnSide = navigationAudioCoordinator.lastTurnSide(),
            )
        } else {
            null
        }

        val inRoadDanger = sidewalk.level == SidewalkNotificationSystem.Level.ROAD ||
            sidewalk.level == SidewalkNotificationSystem.Level.DRIFT

        val imuBeeps = !inRoadDanger && navAlignment != null && !navAlignment.aligned &&
            (navAlignment.guideLeftBeep > 0.05f || navAlignment.guideRightBeep > 0.05f)

        val merged = mergeBeeps(
            inRoadDanger = inRoadDanger,
            lateral = lateral,
            doorwayGuide = doorwayGuide,
            junctionBeeps = junctionBeeps,
            imuBeeps = imuBeeps,
            navAlignment = navAlignment,
        )

        val voiceCue = when {
            inRoadDanger -> null
            navAlignment?.voiceCue != null -> navAlignment.voiceCue
            else -> buildMapsVoiceCue(phase, streetLayout, crosswalk, junction, corridor, now)
        }

        val shouldAnnounceFrontal = config.frontalAlertsEnabled &&
            corridor.isFrontallyBlocked &&
            corridor.frontalSeverity >= 0.48f &&
            phase != OutdoorNavPhase.CROSSING &&
            sidewalk.level == SidewalkNotificationSystem.Level.OK &&
            lateral.inSafeZone

        return ExitBrainFrameResult(
            phase = when {
                inRoadDanger -> ExitBrainPhase.BLOCKED
                doorwayGuide.isGuiding -> ExitBrainPhase.ALIGN
                phase == OutdoorNavPhase.TURN_AT_JUNCTION || imuBeeps -> ExitBrainPhase.ALIGN
                else -> ExitBrainPhase.EXPLORE
            },
            leftBeep = merged.left,
            rightBeep = merged.right,
            doorwayMode = merged.doorwayMode,
            continuousTone = merged.continuous,
            guidanceMode = merged.guidanceMode,
            voiceCue = voiceCue,
            isExitGuiding = true,
            junctionType = junction,
            shouldAnnounceFrontal = shouldAnnounceFrontal,
            frontalBlocksExit = shouldAnnounceFrontal,
            doorwayPhase = doorwayGuide.phase,
            turnRemainingDeg = navAlignment?.remainingDeg,
            turnTurnedDeg = navAlignment?.turnedDeg,
            suppressScene = true,
            outdoorPhase = if (inRoadDanger) OutdoorNavPhase.DRIFT_WARNING else phase,
            roadSide = streetLayout.roadSide,
            safeSide = streetLayout.safeSide,
            sidewalkAlignment = streetLayout.alignment,
            driftScore = streetLayout.driftScore,
            centeringScore = streetLayout.centeringScore,
            inSafeZone = lateral.inSafeZone && !imuBeeps && !doorwayGuide.isGuiding,
            sidewalkLeftNorm = lastWalkable.leftEdgeNorm,
            sidewalkRightNorm = lastWalkable.rightEdgeNorm,
            lateralOffsetNorm = lastWalkable.lateralOffsetNorm,
            walkableConfidence = lastWalkable.confidence,
            perceptionSource = lastWalkable.source,
            frontalDistanceM = lastWalkable.frontalDistanceM ?: depthSnapshot.frontalDistanceM,
            mapsInstructionType = navigationAudioCoordinator.lastInstructionType(),
            warningMode = merged.warning,
            justAligned = navAlignment?.justAligned == true && !inRoadDanger,
        )
    }

    private data class MergedBeeps(
        val left: Float,
        val right: Float,
        val continuous: Boolean,
        val warning: Boolean,
        val doorwayMode: Boolean,
        val guidanceMode: Boolean,
    )

    private fun mergeBeeps(
        inRoadDanger: Boolean,
        lateral: LateralGuidanceController.Signal,
        doorwayGuide: DoorwayGuideResult,
        junctionBeeps: JunctionBeepGuide.Signal?,
        imuBeeps: Boolean,
        navAlignment: TurnAlignmentState?,
    ): MergedBeeps {
        if (inRoadDanger) {
            return MergedBeeps(
                left = lateral.leftBeep,
                right = lateral.rightBeep,
                continuous = true,
                warning = true,
                doorwayMode = true,
                guidanceMode = false,
            )
        }

        if (doorwayGuide.isGuiding) {
            return MergedBeeps(
                left = doorwayGuide.leftBeep,
                right = doorwayGuide.rightBeep,
                continuous = doorwayGuide.continuousTone,
                warning = false,
                doorwayMode = true,
                guidanceMode = false,
            )
        }

        if (imuBeeps && navAlignment != null) {
            return MergedBeeps(
                left = navAlignment.guideLeftBeep,
                right = navAlignment.guideRightBeep,
                continuous = navAlignment.continuousGuide,
                warning = false,
                doorwayMode = true,
                guidanceMode = false,
            )
        }

        if (junctionBeeps != null &&
            (lateral.inSafeZone || lateral.leftBeep < 0.2f && lateral.rightBeep < 0.2f)
        ) {
            return MergedBeeps(
                left = maxOf(lateral.leftBeep, junctionBeeps.leftBeep),
                right = maxOf(lateral.rightBeep, junctionBeeps.rightBeep),
                continuous = lateral.continuous || junctionBeeps.continuous,
                warning = false,
                doorwayMode = true,
                guidanceMode = lateral.guidanceMode,
            )
        }

        return MergedBeeps(
            left = lateral.leftBeep,
            right = lateral.rightBeep,
            continuous = lateral.continuous,
            warning = lateral.warning,
            doorwayMode = lateral.guidanceMode,
            guidanceMode = lateral.guidanceMode,
        )
    }

    private fun maybeHaptic(signal: SidewalkNotificationSystem.Signal, now: Long) {
        val minGap = when (signal.haptic) {
            SidewalkNotificationSystem.Signal.Haptic.ROAD_DANGER -> 2_200L
            SidewalkNotificationSystem.Signal.Haptic.NUDGE_LEFT,
            SidewalkNotificationSystem.Signal.Haptic.NUDGE_RIGHT,
            -> 3_500L
            SidewalkNotificationSystem.Signal.Haptic.RECOVERED -> 8_000L
            SidewalkNotificationSystem.Signal.Haptic.NONE -> return
        }
        if (now - lastHapticMs < minGap) return
        lastHapticMs = now
        when (signal.haptic) {
            SidewalkNotificationSystem.Signal.Haptic.ROAD_DANGER ->
                TurnHapticFeedback.pulseObstacle(context)
            SidewalkNotificationSystem.Signal.Haptic.NUDGE_LEFT ->
                TurnHapticFeedback.pulseGuideNudge(context, turnLeft = true)
            SidewalkNotificationSystem.Signal.Haptic.NUDGE_RIGHT ->
                TurnHapticFeedback.pulseGuideNudge(context, turnLeft = false)
            SidewalkNotificationSystem.Signal.Haptic.RECOVERED ->
                TurnHapticFeedback.pulseAligned(context)
            SidewalkNotificationSystem.Signal.Haptic.NONE -> Unit
        }
    }

    private fun buildMapsVoiceCue(
        phase: OutdoorNavPhase,
        streetLayout: StreetLayoutState,
        crosswalk: CrosswalkState,
        junction: JunctionType,
        corridor: CorridorState,
        now: Long,
    ): DoorwayVoiceCue? {
        return when (phase) {
            OutdoorNavPhase.APPROACH_CROSSING -> {
                if (crosswalk.detected) {
                    if (now - lastCrosswalkCueMs < CROSSWALK_DEBOUNCE_MS) return null
                    val message = OutdoorPhraseBuilder.crosswalkFoundPhrase(crosswalk) ?: return null
                    lastCrosswalkCueMs = now
                    DoorwayVoiceCue(message, debounceMs = CROSSWALK_DEBOUNCE_MS, cueId = "outdoor_crosswalk")
                } else {
                    if (now - lastCrossSearchCueMs < CROSS_SEARCH_DEBOUNCE_MS) return null
                    lastCrossSearchCueMs = now
                    DoorwayVoiceCue(
                        OutdoorPhraseBuilder.crossSearchPhrase(),
                        debounceMs = CROSS_SEARCH_DEBOUNCE_MS,
                        cueId = "outdoor_cross_search",
                    )
                }
            }
            OutdoorNavPhase.CROSSING -> {
                if (!crosswalk.detected) return null
                if (now - lastCrosswalkCueMs < CROSSWALK_DEBOUNCE_MS) return null
                val message = OutdoorPhraseBuilder.crosswalkFoundPhrase(crosswalk) ?: return null
                lastCrosswalkCueMs = now
                DoorwayVoiceCue(message, debounceMs = CROSSWALK_DEBOUNCE_MS, cueId = "outdoor_crossing")
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
                DoorwayVoiceCue(message, debounceMs = JUNCTION_DEBOUNCE_MS, cueId = "outdoor_junction")
            }
            OutdoorNavPhase.ARRIVING -> DoorwayVoiceCue(
                OutdoorPhraseBuilder.arrivalPhrase(),
                debounceMs = ARRIVE_DEBOUNCE_MS,
                cueId = "outdoor_arrive",
            )
            else -> null
        }
    }

    companion object {
        private const val DOORWAY_MIN_CONFIDENCE = 0.58f
        private const val CROSS_SEARCH_DEBOUNCE_MS = 12_000L
        private const val CROSSWALK_DEBOUNCE_MS = 10_000L
        private const val JUNCTION_DEBOUNCE_MS = 12_000L
        private const val ARRIVE_DEBOUNCE_MS = 20_000L
    }
}
