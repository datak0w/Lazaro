package io.lazaro.pathguide

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.lazaro.navigation.NavigationAudioCoordinator
import io.lazaro.navigation.NavigationGuidanceMonitor
import io.lazaro.navigation.TurnSide
import io.lazaro.voice.TextToSpeechManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.lazaro.routes.HybridNavigationCoordinator
import io.lazaro.routes.location.GpsFix
import io.lazaro.routes.location.HighAccuracyLocationProvider
import io.lazaro.routes.recording.RouteRecorderController
import io.lazaro.routes.replay.RouteReplayBrain
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PathGuideController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: PathGuideRepository,
    private val pathGuideCameraHost: PathGuideCameraHost,
    private val depthHardwareDetector: DepthHardwareDetector,
    private val stereoBeepEngine: StereoBeepEngine,
    private val doorwayGuideAnnouncer: DoorwayGuideAnnouncer,
    private val obstacleLabeler: ObstacleLabeler,
    private val bypassAdvisor: BypassAdvisor,
    private val sceneDescriber: SceneDescriber,
    private val sceneDescriptionAnnouncer: SceneDescriptionAnnouncer,
    private val foregroundBridge: PathGuideForegroundBridge,
    private val navigationGuidanceMonitor: NavigationGuidanceMonitor,
    private val navigationAudioCoordinator: NavigationAudioCoordinator,
    private val turnAlignmentGuide: TurnAlignmentGuide,
    private val deviceRotationTracker: DeviceRotationTracker,
    private val textToSpeechManager: TextToSpeechManager,
    private val exitPriorityBrain: ExitPriorityBrain,
    private val outdoorNavigationBrain: OutdoorNavigationBrain,
    private val routeRecorderController: Lazy<RouteRecorderController>,
    private val routeReplayBrain: RouteReplayBrain,
    private val hybridNavigationCoordinator: Lazy<HybridNavigationCoordinator>,
    private val highAccuracyLocationProvider: HighAccuracyLocationProvider,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pathGuideAnalyzer = PathGuideAnalyzer()

    private val _mode = MutableStateFlow(PathGuideMode.OFF)
    val mode: StateFlow<PathGuideMode> = _mode.asStateFlow()

    private val _debugState = MutableStateFlow<PathGuideDebugState?>(null)
    val debugState: StateFlow<PathGuideDebugState?> = _debugState.asStateFlow()

    private var config = PathGuideConfig()
    private var frontalStableMs = 0L
    private var lastFrameMs = 0L
    private var lastDebugPublishMs = 0L
    private var lastLabel: String? = null
    private var lastSceneDescription: String? = null
    private var lastSceneDescriptionMs = 0L
    private var sceneDescriptionPending = false
    private var lastTurnRemainingDeg: Float? = null
    private var lastTurnTurnedDeg: Float? = null
    private var lastBrainPhase: ExitBrainPhase = ExitBrainPhase.EXPLORE
    private var lastJunctionType: JunctionType = JunctionType.NONE
    private var lastApproachState: ApproachState = ApproachState()
    private var lastOutdoorPhase: OutdoorNavPhase? = null
    private var lastRoadSide: RoadSide = RoadSide.UNKNOWN
    private var lastSafeSide: RoadSide = RoadSide.UNKNOWN
    private var lastSidewalkAlignment: SidewalkAlignment = SidewalkAlignment.UNKNOWN
    private var lastDriftScore: Float = 0f
    private var lastCenteringScore: Float = 0.5f
    private var lastInSafeZone: Boolean = false
    private var lastGuideLeftBeep: Float = 0f
    private var lastGuideRightBeep: Float = 0f
    private var lastSidewalkLeftNorm: Float = 0.22f
    private var lastSidewalkRightNorm: Float = 0.78f
    private var lastLateralOffsetNorm: Float = 0f
    private var lastWalkableConfidence: Float = 0f
    private var lastDepthGuidanceMode: DepthGuidanceMode = DepthGuidanceMode.MONOCULAR
    private var lastDeviceLabel: String = ""
    private var lastPerceptionSource: PerceptionSource = PerceptionSource.MONOCULAR
    private var lastFrontalDistanceM: Float? = null
    private var lastMapsInstructionType: MapsInstructionType = MapsInstructionType.OTHER
    private var announcing = false
    private var duckJob: Job? = null
    private var locationJob: Job? = null
    private var lastGpsFix: GpsFix? = null
    private var lastRouteFlushMs = 0L
    private var activeRouteId: Long? = null

    init {
        scope.launch {
            repository.config.collect { config = it }
        }
        scope.launch {
            textToSpeechManager.isSpeaking.collect { speaking ->
                val streetGuide = _mode.value == PathGuideMode.PASEO ||
                    _mode.value == PathGuideMode.NAVEGACION ||
                    _mode.value == PathGuideMode.RUTA ||
                    _mode.value == PathGuideMode.DEBUG ||
                    _mode.value == PathGuideMode.GRABANDO
                // En calle: no callar por TTS genérico; solo Maps o anuncio activo.
                stereoBeepEngine.setPaused(
                    announcing ||
                        (streetGuide && navigationAudioCoordinator.shouldDuckBeeps()) ||
                        (!streetGuide && _mode.value != PathGuideMode.OFF && speaking),
                )
            }
        }
        pathGuideCameraHost.setFrameListener { gray, width, height, image ->
            handleFrame(gray, width, height, image)
        }
    }

    fun isActive(): Boolean = _mode.value != PathGuideMode.OFF

    fun currentMode(): PathGuideMode = _mode.value

    suspend fun start(mode: PathGuideMode, routeId: Long? = null): Boolean {
        if (!config.enabled) return false
        if (_mode.value == mode && activeRouteId == routeId) return pathGuideCameraHost.isRunning()
        if (_mode.value != PathGuideMode.OFF) stop()

        if (!hasCameraPermission()) {
            Log.w(TAG, "Permiso de cámara no concedido")
            return false
        }

        _mode.value = mode
        activeRouteId = routeId
        pathGuideAnalyzer.reset()
        turnAlignmentGuide.reset()
        exitPriorityBrain.reset()
        outdoorNavigationBrain.reset()
        routeReplayBrain.reset()
        doorwayGuideAnnouncer.reset()
        sceneDescriptionAnnouncer.reset()
        frontalStableMs = 0L
        lastFrameMs = 0L
        lastSceneDescriptionMs = 0L
        sceneDescriptionPending = false
        lastSceneDescription = null
        lastRouteFlushMs = 0L

        if (mode == PathGuideMode.RUTA && routeId != null) {
            routeReplayBrain.loadRoute(routeId)
        }
        if (mode == PathGuideMode.GRABANDO || mode == PathGuideMode.RUTA) {
            bindHighAccuracyLocation()
        }

        stereoBeepEngine.setVolume(config.volume)
        val streetMode = mode == PathGuideMode.PASEO ||
            mode == PathGuideMode.NAVEGACION ||
            mode == PathGuideMode.RUTA ||
            mode == PathGuideMode.DEBUG ||
            mode == PathGuideMode.GRABANDO
        val depthCaps = depthHardwareDetector.detect(streetMode && config.depthEnhancedGuidance)
        lastDepthGuidanceMode = depthCaps.mode
        lastDeviceLabel = depthCaps.deviceLabel
        outdoorNavigationBrain.configureDepth(depthCaps, streetMode)
        deviceRotationTracker.start()
        stereoBeepEngine.start()

        foregroundBridge.promoteCameraForeground(includeCamera = true)

        val started = pathGuideCameraHost.start(streetMode && config.depthEnhancedGuidance)
        if (!started) {
            stop()
            return false
        }
        return true
    }

    fun stop() {
        _mode.value = PathGuideMode.OFF
        activeRouteId = null
        locationJob?.cancel()
        locationJob = null
        lastGpsFix = null
        navigationAudioCoordinator.setReplaySegmentActive(false)
        foregroundBridge.promoteCameraForeground(includeCamera = false)
        announcing = false
        frontalStableMs = 0L
        _debugState.value = null
        pathGuideCameraHost.stop()
        deviceRotationTracker.stop()
        stereoBeepEngine.stop()
        turnAlignmentGuide.reset()
        exitPriorityBrain.reset()
        outdoorNavigationBrain.reset()
        doorwayGuideAnnouncer.reset()
        sceneDescriptionAnnouncer.reset()
        pathGuideAnalyzer.reset()
        lastSceneDescriptionMs = 0L
        sceneDescriptionPending = false
        lastSceneDescription = null
    }

    private fun handleFrame(
        gray: ByteArray,
        width: Int,
        height: Int,
        image: ImageProxy?,
    ) {
        var closeInFinally = true
        try {
            if (_mode.value == PathGuideMode.OFF) return

            val now = System.currentTimeMillis()
            if (lastFrameMs == 0L) lastFrameMs = now
            val delta = now - lastFrameMs
            lastFrameMs = now

            val corridor = pathGuideAnalyzer.analyze(gray, width, height, config.sensitivity)

            val currentMode = _mode.value

            val brain = when (currentMode) {
                PathGuideMode.NAVEGACION,
                PathGuideMode.PASEO,
                PathGuideMode.DEBUG,
                PathGuideMode.GRABANDO,
                -> outdoorNavigationBrain.update(
                    gray = gray,
                    width = width,
                    height = height,
                    corridor = corridor,
                    deltaMs = delta,
                    config = config,
                    image = image,
                    now = now,
                )
                PathGuideMode.RUTA -> buildRouteReplayBrain(
                    gray = gray,
                    width = width,
                    height = height,
                    corridor = corridor,
                    deltaMs = delta,
                    image = image,
                    now = now,
                )
                else -> exitPriorityBrain.update(
                    corridor = corridor,
                    deltaMs = delta,
                    config = config,
                    mode = currentMode,
                    now = now,
                    obstacleLabel = lastLabel,
                )
            }
            lastBrainPhase = brain.phase
            lastJunctionType = brain.junctionType
            lastApproachState = brain.approachState
            lastTurnRemainingDeg = brain.turnRemainingDeg
            lastTurnTurnedDeg = brain.turnTurnedDeg
            lastOutdoorPhase = brain.outdoorPhase
            lastRoadSide = brain.roadSide
            lastSafeSide = brain.safeSide
            lastSidewalkAlignment = brain.sidewalkAlignment
            lastDriftScore = brain.driftScore
            lastCenteringScore = brain.centeringScore
            lastInSafeZone = brain.inSafeZone
            lastGuideLeftBeep = brain.leftBeep
            lastGuideRightBeep = brain.rightBeep
            lastSidewalkLeftNorm = brain.sidewalkLeftNorm
            lastSidewalkRightNorm = brain.sidewalkRightNorm
            lastLateralOffsetNorm = brain.lateralOffsetNorm
            lastWalkableConfidence = brain.walkableConfidence
            lastPerceptionSource = brain.perceptionSource
            lastFrontalDistanceM = brain.frontalDistanceM
            lastMapsInstructionType = brain.mapsInstructionType

            if (currentMode == PathGuideMode.GRABANDO) {
                handleRecordingFrame(corridor, brain, now)
            }
            if (currentMode == PathGuideMode.RUTA) {
                handlePassiveLearnFrame(corridor, brain, now)
            }

            if (currentMode != PathGuideMode.NAVEGACION &&
                currentMode != PathGuideMode.PASEO &&
                currentMode != PathGuideMode.RUTA &&
                currentMode != PathGuideMode.DEBUG &&
                currentMode != PathGuideMode.GRABANDO
            ) {
                exitPriorityBrain.trackFrontalBlocked(corridor.isFrontallyBlocked, delta)
            }

            val routeMatch = routeReplayBrain.currentMatch()
            publishDebugState(
                gray, width, height, corridor, brain.doorwayPhase,
                now,
                turnRemainingDeg = lastTurnRemainingDeg,
                turnTurnedDeg = lastTurnTurnedDeg,
                brainPhase = brain.phase,
                junctionType = brain.junctionType,
                approachState = brain.approachState,
                routeMatch = routeMatch,
            )

            // Preview DEBUG: aplica pitidos de acera/giro
            if (_mode.value == PathGuideMode.DEBUG) {
                stereoBeepEngine.update(
                    brain.leftBeep,
                    brain.rightBeep,
                    doorwayMode = brain.doorwayMode,
                    continuousTone = brain.continuousTone,
                    warningMode = brain.warningMode,
                    guidanceMode = brain.guidanceMode,
                )
                brain.voiceCue?.takeIf { it.cueId.startsWith("outdoor_") || it.cueId.startsWith("imu_") }
                    ?.let { maybeAnnounceExitCue(it) }
                if (brain.justAligned) onAlignedSuccess()
                return
            }

            if (brain.justAligned) {
                onAlignedSuccess()
            }

            val streetMode = currentMode == PathGuideMode.NAVEGACION ||
                currentMode == PathGuideMode.PASEO ||
                currentMode == PathGuideMode.RUTA

            if (shouldSilenceBeepsForMaps() &&
                brain.outdoorPhase != OutdoorNavPhase.DRIFT_WARNING &&
                brain.sidewalkAlignment != SidewalkAlignment.ON_ROAD &&
                brain.sidewalkAlignment != SidewalkAlignment.DRIFTING_TO_ROAD &&
                brain.leftBeep < 0.05f && brain.rightBeep < 0.05f
            ) {
                stereoBeepEngine.update(0f, 0f)
            } else if (brain.isExitGuiding || streetMode || currentMode == PathGuideMode.GRABANDO) {
                // Pitidos espaciales siempre (paseo / nav / grabación)
                stereoBeepEngine.update(
                    brain.leftBeep,
                    brain.rightBeep,
                    doorwayMode = true, // umbral más sensible para guía
                    continuousTone = brain.continuousTone ||
                        brain.leftBeep >= 0.50f ||
                        brain.rightBeep >= 0.50f,
                    warningMode = brain.warningMode,
                    guidanceMode = brain.guidanceMode,
                )
                val voiceCue = brain.voiceCue?.takeIf { cue ->
                    cue.cueId !in SIDEWALK_VOICE_BLOCKLIST &&
                        cue.cueId != "imu_aligned" && (
                        cue.cueId.startsWith("imu_") ||
                            cue.cueId.contains("coarse") ||
                            cue.cueId.startsWith("outdoor_cross") ||
                            cue.cueId.startsWith("outdoor_junction") ||
                            cue.cueId.startsWith("outdoor_arriv") ||
                            cue.cueId.startsWith("route_")
                        )
                }?.takeIf { cue ->
                    currentMode == PathGuideMode.PASEO ||
                        currentMode == PathGuideMode.GRABANDO ||
                        canSpeakPathGuide(
                            urgent = cue.cueId.startsWith("imu_") ||
                                cue.cueId.startsWith("route_obstacle"),
                        )
                }
                maybeAnnounceExitCue(voiceCue)
                return
            } else {
                stereoBeepEngine.update(
                    brain.leftBeep,
                    brain.rightBeep,
                    doorwayMode = brain.doorwayMode,
                    continuousTone = brain.continuousTone,
                    warningMode = brain.warningMode,
                    guidanceMode = brain.guidanceMode,
                )
                brain.voiceCue?.takeIf {
                    it.cueId != "imu_aligned" &&
                        it.cueId !in SIDEWALK_VOICE_BLOCKLIST &&
                        it.cueId.contains("coarse")
                }?.let { cue ->
                    if (canSpeakPathGuide(urgent = false)) {
                        maybeAnnounceExitCue(cue)
                    }
                }
            }

            if (corridor.isFrontallyBlocked && corridor.doorwayActive) {
                frontalStableMs = 0L
                return
            }

            if (brain.shouldAnnounceFrontal && !announcing && canSpeakPathGuide(urgent = true)) {
                announcing = true
                val snapshotCorridor = corridor
                scope.launch {
                    try {
                        stereoBeepEngine.setPaused(true)
                        io.lazaro.navigation.TurnHapticFeedback.pulseObstacle(context)
                        val advice = bypassAdvisor.advise(snapshotCorridor)
                        val message = SpatialPhraseBuilder.frontalObstaclePhrase(
                            label = "obstáculo",
                            corridor = snapshotCorridor,
                            advice = advice,
                            mode = _mode.value,
                        )
                        textToSpeechManager.initialize()
                        textToSpeechManager.speak(message)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error anunciando obstáculo frontal", e)
                    } finally {
                        announcing = false
                        frontalStableMs = 0L
                        exitPriorityBrain.onFrontalAnnounced()
                        refreshBeepPause()
                    }
                }
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error en análisis de frame", e)
        } finally {
            if (closeInFinally && image != null) {
                try {
                    image.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun onAlignedSuccess() {
        stereoBeepEngine.playSuccessCoin()
        io.lazaro.navigation.TurnHapticFeedback.pulseAligned(context)
        if (!announcing && canSpeakPathGuide(urgent = true)) {
            maybeAnnounceExitCue(
                DoorwayVoiceCue(
                    message = "Perfecto. Sigue recto.",
                    debounceMs = 8_000L,
                    cueId = "imu_aligned",
                ),
            )
        }
    }

    private fun maybeDescribeScene(
        image: ImageProxy?,
        corridor: CorridorState,
        doorwayPhase: DoorwayGuidePhase,
        now: Long,
        allowDuringDoorway: Boolean,
        suppressScene: Boolean = false,
    ): Boolean {
        // Identificación de objetos / descripciones de escena desactivadas:
        // priorizamos pitidos + IMU para navegación.
        return false
    }

    private fun maybeAnnounceExitCue(cue: DoorwayVoiceCue?) {
        if (cue == null || announcing || !config.doorwayAlertsEnabled) return
        val isImuCue = cue.cueId.startsWith("imu_")
        if (_mode.value == PathGuideMode.NAVEGACION || _mode.value == PathGuideMode.RUTA) {
            if (!isImuCue && !cue.cueId.startsWith("exit_") && !cue.cueId.startsWith("outdoor_") &&
                !cue.cueId.startsWith("route_")
            ) return
        }
        if (!canSpeakPathGuide(urgent = isImuCue || cue.cueId.startsWith("exit_"))) return
        announcing = true
        scope.launch {
            try {
                stereoBeepEngine.setPaused(true)
                doorwayGuideAnnouncer.announce(cue)
            } catch (e: Exception) {
                Log.e(TAG, "Error anunciando guía de salida", e)
            } finally {
                announcing = false
                refreshBeepPause()
            }
        }
    }

    private fun refreshBeepPause() {
        val streetGuide = _mode.value == PathGuideMode.PASEO ||
            _mode.value == PathGuideMode.NAVEGACION ||
            _mode.value == PathGuideMode.RUTA ||
            _mode.value == PathGuideMode.DEBUG ||
            _mode.value == PathGuideMode.GRABANDO
        stereoBeepEngine.setPaused(
            announcing || (streetGuide && navigationAudioCoordinator.shouldDuckBeeps()),
        )
    }

    private fun maybeAnnounceDoorwayCue(cue: DoorwayVoiceCue?) {
        maybeAnnounceExitCue(cue)
    }

    private fun publishDebugState(
        gray: ByteArray,
        width: Int,
        height: Int,
        corridor: CorridorState,
        doorwayPhase: DoorwayGuidePhase,
        now: Long,
        turnRemainingDeg: Float? = null,
        turnTurnedDeg: Float? = null,
        brainPhase: ExitBrainPhase = ExitBrainPhase.EXPLORE,
        junctionType: JunctionType = JunctionType.NONE,
        approachState: ApproachState = ApproachState(),
        routeMatch: io.lazaro.routes.model.RouteMatchState? = null,
    ) {
        if (now - lastDebugPublishMs < DEBUG_FRAME_MS) return
        lastDebugPublishMs = now
        try {
            _debugState.value?.frame?.recycle()
            val bitmap = GrayBitmapConverter.toBitmap(gray, width, height)
            _debugState.value = PathGuideDebugState(
                frame = bitmap,
                frameWidth = width,
                frameHeight = height,
                mode = _mode.value,
                corridor = corridor,
                doorwayPhase = doorwayPhase,
                stairState = StairState(),
                handrailSide = HandrailSide.UNKNOWN,
                lastLabel = lastLabel,
                lastSceneDescription = lastSceneDescription,
                turnRemainingDeg = turnRemainingDeg,
                turnTurnedDeg = turnTurnedDeg,
                brainPhase = brainPhase,
                junctionType = junctionType,
                approachState = approachState,
                outdoorPhase = lastOutdoorPhase,
                roadSide = lastRoadSide,
                safeSide = lastSafeSide,
                sidewalkAlignment = lastSidewalkAlignment,
                driftScore = lastDriftScore,
                centeringScore = lastCenteringScore,
                inSafeZone = lastInSafeZone,
                guideLeftBeep = lastGuideLeftBeep,
                guideRightBeep = lastGuideRightBeep,
                sidewalkLeftNorm = lastSidewalkLeftNorm,
                sidewalkRightNorm = lastSidewalkRightNorm,
                mapsInstructionType = lastMapsInstructionType,
                stairPeaks = 0,
                routeMatchConfidence = routeMatch?.confidence,
                routeLateralOffsetM = routeMatch?.lateralOffsetM,
                routeInReplaySegment = routeMatch?.inReplaySegment == true,
                routeExpectedLeftP = routeMatch?.expectedPoint?.leftP,
                routeExpectedRightP = routeMatch?.expectedPoint?.rightP,
                odmScore = routeMatch?.odmScore,
                odmAlongM = routeMatch?.odmAlongM,
                odmGradePct = routeMatch?.odmGradePct,
                onOdmCorridor = routeMatch?.onOdmCorridor == true,
                lateralOffsetNorm = lastLateralOffsetNorm,
                walkableConfidence = lastWalkableConfidence,
                perceptionSource = lastPerceptionSource,
                depthGuidanceMode = lastDepthGuidanceMode,
                deviceLabel = lastDeviceLabel,
                frontalDistanceM = lastFrontalDistanceM,
                updatedAtMs = now,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error publicando debug", e)
        }
    }

    private fun shouldSilenceBeepsForMaps(): Boolean {
        return (_mode.value == PathGuideMode.NAVEGACION || _mode.value == PathGuideMode.RUTA) &&
            navigationAudioCoordinator.shouldDuckBeeps()
    }

    private fun canSpeakPathGuide(urgent: Boolean): Boolean {
        if (_mode.value != PathGuideMode.NAVEGACION && _mode.value != PathGuideMode.RUTA) return true
        return navigationAudioCoordinator.canPathGuideSpeak(urgent)
    }

    private fun applyNavigationTurnBias(left: Float, right: Float): Pair<Float, Float> {
        if (_mode.value != PathGuideMode.NAVEGACION && _mode.value != PathGuideMode.RUTA) return left to right
        return when (navigationAudioCoordinator.lastTurnSide()) {
            TurnSide.LEFT -> left to right * 0.65f
            TurnSide.RIGHT -> left * 0.65f to right
            TurnSide.U_TURN -> left * 0.8f to right * 0.8f
            null -> left to right
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun bindHighAccuracyLocation() {
        locationJob?.cancel()
        locationJob = highAccuracyLocationProvider.fixes()
            .onEach { fix -> lastGpsFix = fix }
            .launchIn(scope)
    }

    private fun handleRecordingFrame(
        corridor: CorridorState,
        brain: ExitBrainFrameResult,
        now: Long,
    ) {
        val fix = lastGpsFix ?: return
        val obstacle = when {
            !lastLabel.isNullOrBlank() -> lastLabel
            corridor.isFrontallyBlocked && corridor.frontalSeverity >= 0.45f -> "obstáculo"
            else -> null
        }
        if (obstacle != null) {
            lastLabel = obstacle
        }
        routeRecorderController.get().onCorridorSample(
            corridor = corridor,
            junction = brain.junctionType,
            safeSide = brain.safeSide,
            roadSide = brain.roadSide,
            obstacleLabel = obstacle,
            lat = fix.lat,
            lng = fix.lng,
        )
        if (now - lastRouteFlushMs >= RECORDING_FLUSH_MS) {
            lastRouteFlushMs = now
            routeRecorderController.get().appendPeriodicFlush()
        }
    }

    private fun handlePassiveLearnFrame(
        corridor: CorridorState,
        brain: ExitBrainFrameResult,
        now: Long,
    ) {
        val match = routeReplayBrain.currentMatch() ?: return
        if (!match.inReplaySegment || match.confidence < 0.55f) return
        val fix = lastGpsFix ?: return
        val obstacle = when {
            !lastLabel.isNullOrBlank() -> lastLabel
            corridor.isFrontallyBlocked && corridor.frontalSeverity >= 0.45f -> "obstáculo"
            else -> null
        }
        routeRecorderController.get().onCorridorSample(
            corridor = corridor,
            junction = brain.junctionType,
            safeSide = brain.safeSide,
            roadSide = brain.roadSide,
            obstacleLabel = obstacle,
            lat = fix.lat,
            lng = fix.lng,
            phase = "REPLAY_LEARN",
        )
        if (now - lastRouteFlushMs >= RECORDING_FLUSH_MS) {
            lastRouteFlushMs = now
            routeRecorderController.get().appendPeriodicFlush()
        }
    }

    private fun buildRouteReplayBrain(
        gray: ByteArray,
        width: Int,
        height: Int,
        corridor: CorridorState,
        deltaMs: Long,
        image: ImageProxy?,
        now: Long,
    ): ExitBrainFrameResult {
        val fix = lastGpsFix
        val replayUpdate = if (fix != null) {
            routeReplayBrain.update(
                corridor = corridor,
                lat = fix.lat,
                lng = fix.lng,
                yawDeg = deviceRotationTracker.currentYawDeg(),
                accuracyM = fix.accuracyM,
                now = now,
            )
        } else {
            ExitBrainFrameResult(phase = ExitBrainPhase.EXPLORE)
        }

        val match = routeReplayBrain.currentMatch()
        if (match != null) {
            hybridNavigationCoordinator.get().updateReplayMetrics(
                match.confidence,
                match.lateralOffsetM,
                match.inReplaySegment,
            )
        }

        val useReplay = match?.inReplaySegment == true && (match.confidence >= 0.7f)
        return if (useReplay) {
            replayUpdate
        } else {
            outdoorNavigationBrain.update(
                gray = gray,
                width = width,
                height = height,
                corridor = corridor,
                deltaMs = deltaMs,
                config = config,
                image = image,
                now = now,
            )
        }
    }

    fun shutdown() {
        stop()
    }

    companion object {
        private const val TAG = "PathGuideController"
        private const val DEBUG_FRAME_MS = 200L
        private const val RECORDING_FLUSH_MS = 8_000L
        private val STREET_URGENT_CUES = setOf(
            "outdoor_crosswalk",
            "outdoor_cross_search",
            "outdoor_crossing",
            "outdoor_junction",
            "outdoor_arrive",
            "route_obstacle",
            "route_drift",
        )

        /** Acera/calzada: solo pitidos, nunca voz. */
        private val SIDEWALK_VOICE_BLOCKLIST = setOf(
            "outdoor_road",
            "outdoor_drift",
            "outdoor_recovered",
            "outdoor_hug",
            "outdoor_sidewalk",
        )
    }
}
