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
    private val rearCameraAnalyzer: RearCameraAnalyzer,
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
                val duck = when {
                    announcing -> true
                    _mode.value == PathGuideMode.NAVEGACION || _mode.value == PathGuideMode.RUTA ->
                        navigationAudioCoordinator.shouldDuckBeeps()
                    _mode.value != PathGuideMode.OFF && speaking -> true
                    else -> false
                }
                stereoBeepEngine.setPaused(duck)
            }
        }
        rearCameraAnalyzer.setFrameListener { gray, width, height, image ->
            handleFrame(gray, width, height, image)
        }
    }

    fun isActive(): Boolean = _mode.value != PathGuideMode.OFF

    fun currentMode(): PathGuideMode = _mode.value

    suspend fun start(mode: PathGuideMode, routeId: Long? = null): Boolean {
        if (!config.enabled) return false
        if (_mode.value == mode && activeRouteId == routeId) return rearCameraAnalyzer.isRunning()
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
        deviceRotationTracker.start()
        if (mode != PathGuideMode.DEBUG) {
            stereoBeepEngine.start()
        }

        foregroundBridge.promoteCameraForeground(includeCamera = true)

        val started = rearCameraAnalyzer.start()
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
        rearCameraAnalyzer.stop()
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
        image: ImageProxy,
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
                PathGuideMode.NAVEGACION -> outdoorNavigationBrain.update(
                    gray = gray,
                    width = width,
                    height = height,
                    corridor = corridor,
                    deltaMs = delta,
                    config = config,
                    now = now,
                )
                PathGuideMode.RUTA -> buildRouteReplayBrain(
                    gray = gray,
                    width = width,
                    height = height,
                    corridor = corridor,
                    deltaMs = delta,
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
            lastMapsInstructionType = brain.mapsInstructionType

            if (currentMode == PathGuideMode.GRABANDO) {
                handleRecordingFrame(corridor, brain, now)
            }

            if (currentMode != PathGuideMode.NAVEGACION && currentMode != PathGuideMode.RUTA) {
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

            if (_mode.value == PathGuideMode.DEBUG) {
                return
            }

            if (shouldSilenceBeepsForMaps()) {
                stereoBeepEngine.update(0f, 0f)
            } else if (brain.isExitGuiding && config.doorwayAlertsEnabled) {
                val (left, right) = applyNavigationTurnBias(brain.leftBeep, brain.rightBeep)
                stereoBeepEngine.update(
                    left,
                    right,
                    doorwayMode = brain.doorwayMode,
                    continuousTone = brain.continuousTone,
                    warningMode = brain.warningMode,
                )
                val voiceCue = if (currentMode == PathGuideMode.NAVEGACION || currentMode == PathGuideMode.RUTA) {
                    brain.voiceCue?.takeIf { canSpeakPathGuide(urgent = brain.voiceCue.cueId.startsWith("imu_") || brain.voiceCue.cueId.startsWith("route_")) }
                } else {
                    brain.voiceCue
                }
                maybeAnnounceExitCue(voiceCue)
                if (maybeDescribeScene(
                        image = image,
                        corridor = corridor,
                        doorwayPhase = brain.doorwayPhase,
                        now = now,
                        allowDuringDoorway = false,
                        suppressScene = brain.suppressScene,
                    )
                ) {
                    closeInFinally = false
                }
                return
            } else {
                val (left, right) = applyNavigationTurnBias(brain.leftBeep, brain.rightBeep)
                stereoBeepEngine.update(
                    left,
                    right,
                    doorwayMode = brain.doorwayMode,
                    warningMode = brain.warningMode,
                )
                brain.voiceCue?.let { cue ->
                    if (canSpeakPathGuide(urgent = cue.cueId.startsWith("imu_"))) {
                        maybeAnnounceExitCue(cue)
                    }
                }
            }

            if (maybeDescribeScene(
                    image = image,
                    corridor = corridor,
                    doorwayPhase = brain.doorwayPhase,
                    now = now,
                    allowDuringDoorway = true,
                    suppressScene = brain.suppressScene,
                )
            ) {
                closeInFinally = false
            }

            if (corridor.isFrontallyBlocked && corridor.doorwayActive) {
                frontalStableMs = 0L
                return
            }

            if (brain.shouldAnnounceFrontal && !announcing && canSpeakPathGuide(urgent = true)) {
                announcing = true
                closeInFinally = false
                val snapshotCorridor = corridor
                scope.launch {
                    try {
                        stereoBeepEngine.setPaused(true)
                        val sceneLabels = obstacleLabeler.analyzeScene(image)
                        lastLabel = sceneLabels.primaryOrDefault()
                        if (sceneLabels.items.isEmpty() && snapshotCorridor.frontalSeverity < 0.36f) {
                            return@launch
                        }
                        val advice = bypassAdvisor.advise(snapshotCorridor)
                        val message = SpatialPhraseBuilder.frontalObstaclePhrase(
                            label = sceneLabels.primaryOrDefault(),
                            corridor = snapshotCorridor,
                            advice = advice,
                            mode = _mode.value,
                        )
                        textToSpeechManager.initialize()
                        textToSpeechManager.speak(message)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error anunciando obstáculo frontal", e)
                    } finally {
                        try {
                            image.close()
                        } catch (_: Exception) {
                        }
                        announcing = false
                        frontalStableMs = 0L
                        exitPriorityBrain.onFrontalAnnounced()
                    }
                }
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error en análisis de frame", e)
        } finally {
            if (closeInFinally) {
                try {
                    image.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun maybeDescribeScene(
        image: ImageProxy,
        corridor: CorridorState,
        doorwayPhase: DoorwayGuidePhase,
        now: Long,
        allowDuringDoorway: Boolean,
        suppressScene: Boolean = false,
    ): Boolean {
        if (!config.sceneDescriptionsEnabled) return false
        if (announcing || sceneDescriptionPending || suppressScene) return false
        if (!allowDuringDoorway && corridor.doorwayActive) return false
        if (!navigationAudioCoordinator.canSceneDescription(now)) return false

        val intervalSec = navigationAudioCoordinator.sceneDescriptionIntervalSec(
            config.sceneDescriptionIntervalSec,
        )
        val intervalMs = intervalSec.coerceIn(15, 120) * 1_000L
        if (now - lastSceneDescriptionMs < intervalMs) return false

        lastSceneDescriptionMs = now
        sceneDescriptionPending = true
        val snapshotCorridor = corridor
        val snapshotDoorwayPhase = doorwayPhase

        scope.launch {
            try {
                val labels = obstacleLabeler.analyzeScene(image)
                val snapshot = SceneSnapshot(
                    labels = labels,
                    corridor = snapshotCorridor,
                    stairDetected = false,
                    doorwayActive = snapshotCorridor.doorwayActive,
                    doorwayPhase = snapshotDoorwayPhase,
                    frontal = FrontalObstacleState(
                        blocked = snapshotCorridor.isFrontallyBlocked,
                        severity = snapshotCorridor.frontalSeverity,
                        closeRange = snapshotCorridor.frontalCloseRange,
                    ),
                )
                val description = sceneDescriber.describe(
                    snapshot = snapshot,
                    mapsInstruction = navigationAudioCoordinator.lastMapsInstruction.value,
                )
                lastSceneDescription = description
                if (!announcing && canSpeakPathGuide(urgent = false)) {
                    stereoBeepEngine.setPaused(true)
                    sceneDescriptionAnnouncer.announce(
                        message = description,
                        minIntervalMs = intervalMs,
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error describiendo escena", e)
            } finally {
                sceneDescriptionPending = false
                try {
                    image.close()
                } catch (_: Exception) {
                }
            }
        }
        return true
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
            }
        }
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
                mapsInstructionType = lastMapsInstructionType,
                stairPeaks = 0,
                routeMatchConfidence = routeMatch?.confidence,
                routeLateralOffsetM = routeMatch?.lateralOffsetM,
                routeInReplaySegment = routeMatch?.inReplaySegment == true,
                routeExpectedLeftP = routeMatch?.expectedPoint?.leftP,
                routeExpectedRightP = routeMatch?.expectedPoint?.rightP,
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
        routeRecorderController.get().onCorridorSample(
            corridor = corridor,
            junction = brain.junctionType,
            safeSide = brain.safeSide,
            roadSide = brain.roadSide,
            obstacleLabel = lastLabel,
            lat = fix.lat,
            lng = fix.lng,
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
    }
}
