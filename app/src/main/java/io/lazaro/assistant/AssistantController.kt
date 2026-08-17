package io.lazaro.assistant

import io.lazaro.actions.ActionExecutor
import io.lazaro.actions.ActionResult
import io.lazaro.actions.ToolName
import io.lazaro.audiobook.BookReaderAction
import io.lazaro.ai.AssistantReply
import io.lazaro.ai.GeminiOrchestrator
import io.lazaro.ai.MemoryExtractor
import io.lazaro.cane.CaneButtonAction
import io.lazaro.memory.MemoryContextBuilder
import io.lazaro.memory.MemoryRepository
import io.lazaro.navigation.NavigationGuidanceMonitor
import io.lazaro.navigation.NavigationSessionManager
import io.lazaro.navigation.NavigationTarget
import io.lazaro.navigation.MapsSessionCloser
import io.lazaro.pathguide.PathGuideController
import io.lazaro.pathguide.PathGuideMode
import io.lazaro.routes.recording.RouteRecorderController
import io.lazaro.voice.ListeningProfile
import io.lazaro.voice.SamsungVoiceCompat
import io.lazaro.voice.SoftWaitToneEngine
import io.lazaro.voice.SpeechRecognitionManager
import io.lazaro.voice.TextToSpeechManager
import io.lazaro.voice.VoiceState
import io.lazaro.voice.WakeWordController
import io.lazaro.voice.WakeWordDetector
import io.lazaro.voice.WakeWordMatch
import io.lazaro.voice.WakeWordNotifier
import io.lazaro.voice.WakeWordStatus
import io.lazaro.phone.CallLifecycleEvent
import io.lazaro.phone.IncomingCallMonitor
import io.lazaro.alarm.AlarmRingingCoordinator
import android.content.Context
import android.media.AudioManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

data class AssistantUiState(
    val voiceState: VoiceState = VoiceState.Idle,
    val isServiceRunning: Boolean = false,
    val partialTranscript: String = "",
    val lastResponse: String = "",
    val statusMessage: String = "",
    val hasApiKey: Boolean = true,
    val awaitingTrigger: Boolean = true,
    val audioLevel: Float = 0f,
    val wakeWordStatus: WakeWordStatus = WakeWordStatus.OFF,
)

@Singleton
class AssistantController @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val speechRecognitionManager: SpeechRecognitionManager,
    private val textToSpeechManager: TextToSpeechManager,
    private val geminiOrchestrator: GeminiOrchestrator,
    private val memoryExtractor: MemoryExtractor,
    private val memoryContextBuilder: MemoryContextBuilder,
    private val memoryRepository: MemoryRepository,
    private val actionExecutor: ActionExecutor,
    private val bookReaderAction: BookReaderAction,
    private val conversationContext: ConversationContext,
    private val contextIntentDetector: ContextIntentDetector,
    private val navigationGuidanceMonitor: NavigationGuidanceMonitor,
    private val navigationSessionManager: NavigationSessionManager,
    private val mapsSessionCloser: MapsSessionCloser,
    private val stopActiveSessionHandler: StopActiveSessionHandler,
    private val pathGuideController: PathGuideController,
    private val wakeWordController: WakeWordController,
    private val wakeWordNotifier: WakeWordNotifier,
    private val softWaitToneEngine: SoftWaitToneEngine,
    private val caneLowBatteryMonitor: io.lazaro.cane.CaneLowBatteryMonitor,
    private val routeRecorderController: RouteRecorderController,
    private val activeSessionTracker: ActiveSessionTracker,
    private val proactiveSuggestionEngine: ProactiveSuggestionEngine,
    private val sleepModeController: SleepModeController,
    private val blindStatusSpeaker: BlindStatusSpeaker,
    private val assistantPrefs: AssistantPrefsRepository,
    private val batteryOptimizationHelper: BatteryOptimizationHelper,
    private val caneBleManager: io.lazaro.cane.ble.CaneBleManager,
    private val incomingCallMonitor: IncomingCallMonitor,
) {
    private val _uiState = MutableStateFlow(
        AssistantUiState(hasApiKey = geminiOrchestrator.hasApiKey()),
    )
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    private var scope: CoroutineScope? = null
    private var isActive = false
    private var isSpeaking = false
    private var listenProfile = ListeningProfile.STANDBY
    private var silentRetries = 0
    private var pendingSilentRetries = 0
    private val userAbortDuringSpeech = AtomicBoolean(false)
    private var resumeListeningJob: Job? = null
    private var processingJob: Job? = null
    private var watchdogJob: Job? = null
    private var navigationPauseJob: Job? = null
    private var conversationWindowJob: Job? = null
    private var listeningSuspended = false
    private var voiceCaptureInProgress = false
    private var lastStateChangeMs = System.currentTimeMillis()
    private var lastWakeHandledMs = 0L
    private var lastVolumeUpMs = 0L
    /** Comando que Vosk ya oyó tras «Lázaro»; fallback si Google STT llega vacío. */
    private var pendingWakeCommand: String = ""

    fun bind(scope: CoroutineScope) {
        this.scope = scope
        wakeWordController.bind(scope, ::onWakeWordDetected)
        scope.launch {
            sleepModeController.hydrateFromPrefs()
            wakeWordController.setSleepMode(sleepModeController.isSleeping())
        }
        scope.launch {
            incomingCallMonitor.incomingCalls.collect { event ->
                handleIncomingCall(event.displayName, event.phoneNumber)
            }
        }
        scope.launch {
            incomingCallMonitor.lifecycle.collect { event ->
                when (event) {
                    CallLifecycleEvent.OFFHOOK,
                    CallLifecycleEvent.IDLE,
                    -> {
                        actionExecutor.clearIncomingCallPending()
                        conversationContext.clearPending()
                    }
                    CallLifecycleEvent.RINGING -> Unit
                }
            }
        }
        scope.launch {
            speechRecognitionManager.audioLevel.collect { level ->
                if (!isSpeaking) {
                    _uiState.update { it.copy(audioLevel = level) }
                }
            }
        }
        scope.launch {
            speechRecognitionManager.partialText.collect { partial ->
                if (partial.isBlank()) return@collect
                if (_uiState.value.voiceState != VoiceState.Listening) return@collect
                _uiState.update { it.copy(partialTranscript = partial) }
            }
        }
        scope.launch {
            wakeWordController.status.collect { status ->
                _uiState.update { it.copy(wakeWordStatus = status) }
            }
        }
    }

    suspend fun initializeVoice() {
        textToSpeechManager.initialize(Locale("es", "ES"))
    }

    fun setServiceRunning(running: Boolean) {
        _uiState.update { it.copy(isServiceRunning = running) }
    }

    fun startAssistant() {
        if (isActive) return
        isActive = true
        resetCounters()
        listenProfile = ListeningProfile.STANDBY
        startWatchdog()
        caneBleManager.setSleepBlocked(false)
        incomingCallMonitor.start()
        caneLowBatteryMonitor.start { _uiState.value.voiceState }
        scope?.launch {
            // Hidratar y marcar sleep ANTES de arrancar Vosk: si no, «Lázaro» abre STT en dormir.
            sleepModeController.hydrateFromPrefs()
            val sleeping = sleepModeController.isSleeping()
            wakeWordController.setSleepMode(sleeping)
            pathGuideController.setSleepMuted(sleeping)
            assistantPrefs.setWantsAssistantRunning(true)
            wakeWordController.start()
            if (sleeping) {
                softWaitToneEngine.stop()
                speechRecognitionManager.stopListening()
                voiceCaptureInProgress = false
                markState(
                    VoiceState.Idle,
                    "Modo dormir. Di Lázaro despierta.",
                    awaitingTrigger = true,
                )
                restoreWakeWordPassive()
            } else {
                blindStatusSpeaker.announceReady()
                batteryOptimizationHelper.speakAndRequestOnce { msg ->
                    textToSpeechManager.speak(msg)
                }
                returnToStandby()
            }
        }
    }

    fun stopAssistant(clearWantedFlag: Boolean = false) {
        isActive = false
        listeningSuspended = false
        navigationPauseJob?.cancel()
        conversationWindowJob?.cancel()
        softWaitToneEngine.stop()
        caneLowBatteryMonitor.stop()
        forceStopOutput()
        wakeWordController.stop()
        incomingCallMonitor.stop()
        wakeWordNotifier.clearListeningNotification()
        navigationGuidanceMonitor.stopNavigation()
        scope?.launch {
            if (clearWantedFlag) {
                assistantPrefs.setWantsAssistantRunning(false)
            }
            try {
                if (routeRecorderController.isCapturingSamples()) {
                    routeRecorderController.finishPassiveLearn()
                }
            } catch (_: Exception) {
            }
            pathGuideController.stop()
        }
        resetCounters()
        stopWatchdog()
        speechRecognitionManager.shutdown()
        markState(VoiceState.Idle, "Asistente detenido.", awaitingTrigger = true)
    }

    fun interruptAndListen() {
        if (!isActive) return
        if (sleepModeController.isSleeping()) return
        if (actionExecutor.isVoiceNoteRecording()) {
            scope?.launch { finishVoiceNoteFromCane() }
            return
        }
        listeningSuspended = false
        navigationPauseJob?.cancel()
        processingJob?.cancel()
        processingJob = null
        forceStopOutput()
        resetCounters()
        pendingWakeCommand = ""
        listenProfile = ListeningProfile.DIRECT_RESPONSE
        wakeWordNotifier.playActivationSound()
        wakeWordController.pauseForCommand()
        val hint = if (actionExecutor.hasPendingConfirmation()) {
            "Responde, repíteme las opciones, o di cancela."
        } else {
            "Te escucho."
        }
        markState(VoiceState.Listening, hint, awaitingTrigger = false)
        // Esperar al chirp + liberar mic de Vosk antes de abrir Google ASR.
        scope?.launch {
            delay(MIC_HANDOFF_DELAY_MS)
            if (!isActive || listeningSuspended || isSpeaking) return@launch
            startDirectListening(force = true, skipPause = true)
        }
    }

    /**
     * Llamada entrante: anuncia quién es y pregunta si responder.
     * También despierta del modo dormir.
     */
    private fun handleIncomingCall(displayName: String, phoneNumber: String) {
        if (!isActive) return
        scope?.launch {
            // Despertar completo (wake + BLE + unmute path guide)
            if (sleepModeController.isSleeping()) {
                exitSleepMode()
            }
            listeningSuspended = false
            navigationPauseJob?.cancel()
            processingJob?.cancel()
            processingJob = null
            softWaitToneEngine.stop()
            cancelScheduledListen()
            speechRecognitionManager.stopListening()
            isSpeaking = false
            textToSpeechManager.stop()
            bookReaderAction.stopPlayback()
            voiceCaptureInProgress = false

            val result = actionExecutor.prepareIncomingCallPrompt(displayName, phoneNumber)
            val prompt = when (result) {
                is ActionResult.NeedsConfirmation -> result.prompt
                is ActionResult.Success -> result.message
                is ActionResult.Error -> result.message
            }
            conversationContext.recordPending("responder o rechazar la llamada", prompt)
            listenProfile = ListeningProfile.DIRECT_RESPONSE
            speakOnly(prompt)
            openConversationWindow()
            resumePendingInput()
        }
    }

    /** Botones del bastón WeWALK (P2P fe42). */
    fun handleCaneButton(action: CaneButtonAction) {
        if (!isActive && action != CaneButtonAction.VOLUME_UP && action != CaneButtonAction.VOLUME_DOWN) {
            return
        }
        when (action) {
            CaneButtonAction.LISTEN -> {
                if (sleepModeController.isSleeping()) return
                if (actionExecutor.isVoiceNoteRecording()) {
                    scope?.launch { finishVoiceNoteFromCane() }
                    return
                }
                interruptAndListen()
            }
            CaneButtonAction.CANCEL -> {
                if (sleepModeController.isSleeping()) return
                if (actionExecutor.isVoiceNoteRecording()) {
                    scope?.launch {
                        val result = actionExecutor.cancelVoiceNoteRecording()
                        speakOnly(
                            when (result) {
                                is ActionResult.Success -> result.message
                                is ActionResult.Error -> result.message
                                is ActionResult.NeedsConfirmation -> result.prompt
                            },
                        )
                        returnToStandby(delayMs = 0L)
                    }
                    return
                }
                cancelFromCane()
            }
            CaneButtonAction.WHERE_AM_I -> {
                // Flecha arriba → foto / «qué ves»
                if (!sleepModeController.isSleeping()) describeSceneFromCane()
            }
            CaneButtonAction.VOLUME_DOWN -> handleVolumeDown()
            CaneButtonAction.VOLUME_UP -> handleVolumeUp()
        }
    }

    private fun handleVolumeDown() {
        when (sleepModeController.onVolumeDownPressed()) {
            DoubleVolumeResult.ENTER_SLEEP -> scope?.launch { enterSleepMode() }
            DoubleVolumeResult.EXIT_SLEEP -> scope?.launch { exitSleepMode() }
            DoubleVolumeResult.VOLUME_ONLY -> {
                if (!sleepModeController.isSleeping()) adjustMediaVolume(-1)
            }
        }
    }

    /** Un toque vol+ → subir; doble → ¿dónde estoy? */
    private fun handleVolumeUp() {
        if (sleepModeController.isSleeping()) return
        val now = System.currentTimeMillis()
        val isDouble = now - lastVolumeUpMs in 1 until SleepModeController.DOUBLE_VOLUME_WINDOW_MS
        lastVolumeUpMs = if (isDouble) 0L else now
        if (isDouble) {
            whereAmIFromCane()
        } else {
            adjustMediaVolume(+1)
        }
    }

    private fun describeSceneFromCane() {
        if (!isActive) return
        forceStopOutput()
        processingJob?.cancel()
        processingJob = scope?.launch {
            try {
                // Procesar ya (foto+IA); el soft-wait basta — no esperar a «Un momento».
                markState(VoiceState.Processing, "Mirando delante…", awaitingTrigger = false)
                when (val result = actionExecutor.execute(ToolName.DescribeScene.id, emptyMap())) {
                    is ActionResult.Success -> {
                        softWaitToneEngine.stop()
                        speakOnly(result.message)
                        markState(VoiceState.Idle, result.message, awaitingTrigger = true)
                    }
                    is ActionResult.Error -> {
                        softWaitToneEngine.stop()
                        speakOnly(result.message)
                        markState(VoiceState.Idle, result.message, awaitingTrigger = true)
                    }
                    is ActionResult.NeedsConfirmation -> {
                        softWaitToneEngine.stop()
                        speakOnly(result.prompt)
                        markState(VoiceState.Idle, result.prompt, awaitingTrigger = true)
                    }
                }
                resumeListening(directAfter = false)
            } catch (_: CancellationException) {
                softWaitToneEngine.stop()
            } finally {
                if (processingJob === coroutineContext[Job]) {
                    processingJob = null
                }
            }
        }
    }

    suspend fun enterSleepMode() {
        if (sleepModeController.isSleeping()) return
        // No cancelar el job actual: si venimos de processUserSpeech, forceStopOutput
        // mataría este coroutine y dejaría la UI en «Procesando…».
        val selfJob = coroutineContext[Job]
        val otherJob = processingJob
        if (otherJob != null && otherJob !== selfJob) {
            otherJob.cancel()
            if (processingJob === otherJob) processingJob = null
        }
        forceStopOutput(cancelProcessingJob = false)
        listeningSuspended = false
        navigationPauseJob?.cancel()
        conversationWindowJob?.cancel()
        cancelScheduledListen()
        speechRecognitionManager.stopListening()
        voiceCaptureInProgress = false
        pendingWakeCommand = ""

        sleepModeController.enterSleep()
        wakeWordController.setSleepMode(true)
        // No bloquear reconnect BLE: hace falta el bastón para despertar con doble vol−.
        caneBleManager.setSleepBlocked(false)
        pathGuideController.setSleepMuted(true)

        listenProfile = ListeningProfile.STANDBY
        softWaitToneEngine.stop()
        markState(
            VoiceState.Idle,
            "Modo dormir. Di Lázaro despierta.",
            awaitingTrigger = true,
        )
        blindStatusSpeaker.announceEnteringSleep()
        restoreWakeWordPassive()
    }

    suspend fun exitSleepMode() {
        if (!sleepModeController.isSleeping()) return
        softWaitToneEngine.stop()
        textToSpeechManager.stop()
        sleepModeController.exitSleep()
        wakeWordController.setSleepMode(false)
        caneBleManager.setSleepBlocked(false)
        pathGuideController.setSleepMuted(false)
        listeningSuspended = false
        voiceCaptureInProgress = false
        pendingWakeCommand = ""
        blindStatusSpeaker.announceWaking()
        markState(VoiceState.Idle, "Despierto.", awaitingTrigger = true)
        restoreWakeWordPassive()
        returnToStandby(delayMs = 0L)
    }

    private fun cancelFromCane() {
        if (!isActive) return
        listeningSuspended = false
        navigationPauseJob?.cancel()
        forceStopOutput()
        resetCounters()
        scope?.launch {
            if (actionExecutor.hasPendingConfirmation()) {
                actionExecutor.cancelPending()
                conversationContext.clearPending()
            }
            val closingNav = navigationSessionManager.isNavigationActive() ||
                activeSessionTracker.hasActiveSession()
            if (closingNav) {
                navigationSessionManager.endSession(speakConfirmation = false)
                mapsSessionCloser.bringLazaroToFront()
                speakOnly("Navegación cancelada.")
            } else {
                mapsSessionCloser.closeMapsNavigation()
                mapsSessionCloser.bringLazaroToFront()
                speakOnly("Cancelado.")
            }
            markState(VoiceState.Idle, "Listo.", awaitingTrigger = true)
            resumeListening(directAfter = false)
        }
    }

    private fun whereAmIFromCane() {
        if (!isActive) return
        forceStopOutput()
        processingJob?.cancel()
        processingJob = scope?.launch {
            try {
                markState(VoiceState.Processing, "Buscando ubicación…", awaitingTrigger = false)
                when (val result = actionExecutor.execute(ToolName.WhereAmI.id, emptyMap())) {
                    is ActionResult.Success -> {
                        softWaitToneEngine.stop()
                        speakOnly(result.message)
                        markState(VoiceState.Idle, result.message, awaitingTrigger = true)
                    }
                    is ActionResult.Error -> {
                        softWaitToneEngine.stop()
                        speakOnly(result.message)
                        markState(VoiceState.Idle, result.message, awaitingTrigger = true)
                    }
                    is ActionResult.NeedsConfirmation -> {
                        softWaitToneEngine.stop()
                        speakOnly(result.prompt)
                        markState(VoiceState.Idle, result.prompt, awaitingTrigger = true)
                    }
                }
                resumeListening(directAfter = false)
            } catch (_: CancellationException) {
                softWaitToneEngine.stop()
            } finally {
                if (processingJob === coroutineContext[Job]) {
                    processingJob = null
                }
            }
        }
    }

    private fun adjustMediaVolume(direction: Int) {
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = AudioManager.STREAM_MUSIC
        am.adjustStreamVolume(
            stream,
            if (direction > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI,
        )
    }

    private fun resumeListening(directAfter: Boolean = false) {
        if (!isActive || isSpeaking) return
        if (sleepModeController.isSleeping()) {
            returnToStandby(delayMs = 0L)
            return
        }
        if (directAfter || actionExecutor.hasPendingConfirmation()) {
            resumePendingInput()
            return
        }
        listenProfile = ListeningProfile.STANDBY
        resetCounters()
        returnToStandby()
    }

    private fun resumePendingInput() {
        if (!isActive || isSpeaking || listeningSuspended) return
        if (sleepModeController.isSleeping()) return
        cancelScheduledListen()
        listenProfile = ListeningProfile.DIRECT_RESPONSE
        speechRecognitionManager.stopListening()

        resumeListeningJob = scope?.launch {
            delay(SamsungVoiceCompat.pendingListenRetryMs)
            if (!isActive || isSpeaking || listeningSuspended) return@launch
            if (sleepModeController.isSleeping()) return@launch
            if (!actionExecutor.hasPendingConfirmation()) {
                returnToStandby()
                return@launch
            }
            startDirectListening(force = true)
        }
    }

    private fun returnToStandby(delayMs: Long = SamsungVoiceCompat.postSpeechDelayMs) {
        if (!isActive || listeningSuspended) return
        if (sleepModeController.isSleeping()) {
            speechRecognitionManager.stopListening()
            voiceCaptureInProgress = false
            pendingWakeCommand = ""
            softWaitToneEngine.stop()
            markState(
                voiceState = VoiceState.Idle,
                statusMessage = "Modo dormir. Di Lázaro despierta.",
                awaitingTrigger = true,
                partialTranscript = "",
            )
            restoreWakeWordPassive()
            return
        }
        if (actionExecutor.hasPendingConfirmation()) {
            resumePendingInput()
            return
        }
        cancelScheduledListen()
        listenProfile = ListeningProfile.STANDBY

        resumeListeningJob = scope?.launch {
            if (delayMs > 0) delay(delayMs)
            if (!isActive || listeningSuspended) return@launch
            if (isSpeaking) delay(SamsungVoiceCompat.postSpeechDelayMs)
            if (!isActive || listeningSuspended) return@launch
            // Parar Google STT; standby = solo Vosk (sin pitidos de conexión)
            speechRecognitionManager.stopListening()
            voiceCaptureInProgress = false
            markState(
                voiceState = VoiceState.Idle,
                statusMessage = standbyStatusMessage(),
                awaitingTrigger = true,
                partialTranscript = "",
            )
            releaseWakeWordAfterCommand()
        }
    }

    private fun forceStopOutput(cancelProcessingJob: Boolean = true) {
        isSpeaking = false
        voiceCaptureInProgress = false
        pendingWakeCommand = ""
        softWaitToneEngine.stop()
        cancelScheduledListen()
        if (cancelProcessingJob) {
            processingJob?.cancel()
            processingJob = null
        }
        textToSpeechManager.stop()
        bookReaderAction.stopPlayback()
        speechRecognitionManager.stopListening()
    }

    private fun resetCounters() {
        silentRetries = 0
        pendingSilentRetries = 0
    }

    private fun standbyStatusMessage(): String {
        return SamsungVoiceCompat.samsungResumeHint()
            ?: "Listo. Di Lazaro, toca la pantalla o pulsa el botón del bastón."
    }

    /**
     * Vosk detectó «Lázaro» → si ya trae comando, ejecutarlo; si no, abrir Google STT.
     */
    private fun onWakeWordDetected(commandFromWake: String) {
        if (!isActive) return
        val spokenCommand = stripBareWakeFollowup(commandFromWake)
        if (sleepModeController.isSleeping()) {
            // Solo llega aquí si Vosk vio «Lázaro despierta»
            scope?.launch { exitSleepMode() }
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastWakeHandledMs < WAKE_DEBOUNCE_MS) return
        lastWakeHandledMs = now

        Log.i("Lazaro", "wake detected cmd='${spokenCommand.take(80)}'")

        if (spokenCommand.isBlank() &&
            voiceCaptureInProgress &&
            speechRecognitionManager.isActive() &&
            !isSpeaking &&
            processingJob?.isActive != true
        ) {
            wakeWordNotifier.playActivationSound()
            return
        }

        listeningSuspended = false
        navigationPauseJob?.cancel()
        softWaitToneEngine.stop()
        cancelScheduledListen()
        processingJob?.cancel()
        processingJob = null
        isSpeaking = false
        textToSpeechManager.stop()
        bookReaderAction.stopPlayback()

        // Si hay navegación/ruta/paseo/grabación, pausar para chat sin cerrar la sesión.
        syncActiveSessionFromHardware()
        if (activeSessionTracker.hasActiveSession() || navigationSessionManager.isNavigationActive()) {
            if (navigationSessionManager.isNavigationActive() ||
                activeSessionTracker.snapshot()?.kind in setOf(
                    ActiveSessionKind.NAVIGATION,
                    ActiveSessionKind.ROUTE_REPLAY,
                )
            ) {
                navigationSessionManager.pauseForChat()
            } else {
                activeSessionTracker.pauseForChat()
            }
            navigationGuidanceMonitor.lastActionTip()?.let {
                activeSessionTracker.setLastManeuverHint(it)
            }
        }
        speechRecognitionManager.stopListening()

        wakeWordNotifier.playActivationSound()
        wakeWordController.pauseForCommand()
        resetCounters()
        listenProfile = ListeningProfile.DIRECT_RESPONSE

        if (spokenCommand.isNotBlank()) {
            pendingWakeCommand = ""
            voiceCaptureInProgress = false
            scope?.launch { dispatchCommand(spokenCommand) }
            return
        }

        pendingWakeCommand = ""
        voiceCaptureInProgress = true
        markState(
            voiceState = VoiceState.Listening,
            statusMessage = "Te escucho.",
            awaitingTrigger = false,
            partialTranscript = "",
        )

        // Dar tiempo a liberar AudioRecord de Vosk antes de Google (Samsung)
        scope?.launch {
            delay(MIC_HANDOFF_DELAY_MS)
            if (!isActive || listeningSuspended || isSpeaking) return@launch
            if (sleepModeController.isSleeping()) return@launch
            startDirectListening(force = true, skipPause = true)
        }
    }

    /** «despierta» solo tras el wake no es un comando útil si ya estamos despiertos. */
    private fun stripBareWakeFollowup(command: String): String {
        val t = command.trim()
        if (t.isBlank()) return ""
        val n = java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9ñ\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (n == "despierta" || n == "despertar" || n == "despiertate") return ""
        return t
    }

    private fun startDirectListening(force: Boolean = false, skipPause: Boolean = false) {
        if (!isActive || isSpeaking || listeningSuspended) return
        if (sleepModeController.isSleeping()) return

        // Liberar micrófono si quedó una nota de voz a medias.
        if (actionExecutor.isVoiceNoteRecording()) {
            actionExecutor.cancelVoiceNoteRecording()
        }

        if (!force && speechRecognitionManager.isActive()) return

        softWaitToneEngine.stop()
        if (!skipPause) {
            wakeWordController.pauseForCommand()
        }
        voiceCaptureInProgress = true
        val profile = resolveListenProfile()
        markState(
            voiceState = VoiceState.Listening,
            statusMessage = when {
                actionExecutor.hasPendingConfirmation() ->
                    "Esperando respuesta. Di sí, no, un número, repíteme las opciones o cancela."
                profile == ListeningProfile.DIRECT_RESPONSE ->
                    "Te escucho. Responde cuando quieras."
                else -> "Te escucho, dime."
            },
            awaitingTrigger = false,
        )

        speechRecognitionManager.startDirectResponseListening(
            onResult = { text ->
                scope?.launch { handleSpeechResult(text) }
            },
            onError = { message, isSilent ->
                scope?.launch { handleSpeechError(message, isSilent) }
            },
        )
    }

    private fun releaseWakeWordAfterCommand() {
        wakeWordController.releaseAfterCommand()
    }

    private fun resolveCommandText(text: String, wakeMatch: WakeWordMatch? = null): String {
        val match = wakeMatch ?: WakeWordDetector.parse(text)
        return when {
            match.detected && match.command.isNotBlank() -> match.command
            else -> text
        }
    }

    private suspend fun dispatchCommand(command: String) {
        if (sleepModeController.isSleeping()) {
            softWaitToneEngine.stop()
            if (sleepModeController.isWakeFromSleepPhrase(command) ||
                sleepModeController.isWakeFromSleepPhrase("lazaro $command")
            ) {
                exitSleepMode()
            } else {
                markState(
                    VoiceState.Idle,
                    "Modo dormir. Di Lázaro despierta.",
                    awaitingTrigger = true,
                )
                restoreWakeWordPassive()
            }
            return
        }
        // Entrar en sleep antes de «Procesando…» (evita flash y soft-wait).
        if (sleepModeController.isSleepCommand(command)) {
            enterSleepMode()
            return
        }
        markState(
            voiceState = VoiceState.Processing,
            statusMessage = "Procesando…",
            awaitingTrigger = false,
            partialTranscript = command,
        )
        launchProcessUserSpeech(command)
    }

    private fun scheduleListen(delayMs: Long) {
        if (!isActive || isSpeaking || listeningSuspended) return
        cancelScheduledListen()
        resumeListeningJob = scope?.launch {
            if (delayMs > 0) delay(delayMs)
            if (!isActive || isSpeaking || listeningSuspended) return@launch
            when {
                actionExecutor.hasPendingConfirmation() ||
                    listenProfile == ListeningProfile.DIRECT_RESPONSE ->
                    scheduleConversationListen()
                else -> returnToStandby(delayMs = 0L)
            }
        }
    }

    private fun cancelScheduledListen() {
        resumeListeningJob?.cancel()
        resumeListeningJob = null
    }

    private fun resolveListenProfile(): ListeningProfile {
        return if (actionExecutor.hasPendingConfirmation()) {
            ListeningProfile.DIRECT_RESPONSE
        } else {
            listenProfile
        }
    }

    private fun consumePendingWakeCommand(): String {
        val cmd = stripBareWakeFollowup(pendingWakeCommand)
        pendingWakeCommand = ""
        return cmd
    }

    private suspend fun handleSpeechResult(rawText: String) {
        voiceCaptureInProgress = false
        silentRetries = 0

        val text = rawText.trim()
        val fallback = consumePendingWakeCommand()
        Log.i("Lazaro", "handleSpeechResult: ${text.take(100)}")
        if (text.isBlank()) {
            if (fallback.isNotBlank()) {
                dispatchCommand(fallback)
                return
            }
            if (!sleepModeController.isSleeping()) {
                returnToStandby(delayMs = 0L)
            }
            return
        }

        // En modo dormir: solo «Lázaro despierta»; ignora el resto (STT tardío incluido).
        if (sleepModeController.isSleeping()) {
            softWaitToneEngine.stop()
            val wakeMatch = WakeWordDetector.parse(text)
            val combined = if (wakeMatch.detected) {
                "lazaro ${wakeMatch.command}".trim()
            } else {
                text
            }
            if (sleepModeController.isWakeFromSleepPhrase(combined) ||
                sleepModeController.isWakeFromSleepPhrase(text) ||
                (wakeMatch.detected && sleepModeController.isWakeFromSleepPhrase(
                    "lazaro ${wakeMatch.command}",
                ))
            ) {
                exitSleepMode()
            } else {
                markState(
                    VoiceState.Idle,
                    "Modo dormir. Di Lázaro despierta.",
                    awaitingTrigger = true,
                )
                restoreWakeWordPassive()
            }
            return
        }

        // Si suena una alarma de Lázaro, «para/apaga» la apaga antes que la navegación
        if (AlarmRingingCoordinator.isRinging()) {
            actionExecutor.tryHandleAlarmIntent(text)?.let { result ->
                textToSpeechManager.stop()
                when (result) {
                    is ActionResult.Success -> speakOnly(result.message)
                    is ActionResult.Error -> speakOnly(result.message)
                    is ActionResult.NeedsConfirmation -> speakOnly(result.prompt)
                }
                resumeListening(directAfter = false)
                return
            }
        }

        if (contextIntentDetector.isInterruptCommand(text) ||
            contextIntentDetector.isNavigationStopPhrase(text)
        ) {
            handleInterruptCommand(text)
            return
        }

        val wakeMatch = WakeWordDetector.parse(text)
        if (wakeMatch.detected && wakeMatch.command.isBlank()) {
            if (fallback.isNotBlank()) {
                dispatchCommand(fallback)
                return
            }
            listenProfile = ListeningProfile.DIRECT_RESPONSE
            startDirectListening(force = true, skipPause = true)
            return
        }

        val command = resolveCommandText(text, wakeMatch)
        if (command.isBlank()) {
            if (fallback.isNotBlank()) {
                dispatchCommand(fallback)
                return
            }
            startDirectListening(force = true, skipPause = true)
            return
        }
        dispatchCommand(command)
    }

    private suspend fun handleInterruptCommand(rawText: String) {
        processingJob?.cancel()
        processingJob = null
        speechRecognitionManager.stopListening()
        softWaitToneEngine.stop()
        isSpeaking = false
        textToSpeechManager.stop()
        bookReaderAction.stopPlayback()
        cancelScheduledListen()

        val wakeMatch = WakeWordDetector.parse(rawText)
        val command = if (wakeMatch.detected) wakeMatch.command else rawText

        when {
            contextIntentDetector.isCancelPhrase(command) || actionExecutor.isNegative(command) -> {
                processingJob = scope?.launch {
                    when (val result = actionExecutor.cancelPending()) {
                        is ActionResult.Success -> {
                            conversationContext.clearPending()
                            speakOnly(result.message)
                        }
                        is ActionResult.Error -> speakOnly(result.message)
                        is ActionResult.NeedsConfirmation -> speakOnly(result.prompt)
                    }
                    resumeListening(directAfter = false)
                }
            }
            wakeMatch.detected && command.isNotBlank() &&
                !stopActiveSessionHandler.shouldHandleStop(command) ->
                launchProcessUserSpeech(command)
            stopActiveSessionHandler.shouldHandleStop(rawText) -> {
                navigationPauseJob?.cancel()
                listeningSuspended = false
                voiceCaptureInProgress = false
                when (val result = stopActiveSessionHandler.handleStop(rawText)) {
                    is StopSessionResult.Handled -> {
                        softWaitToneEngine.stop()
                        markState(
                            voiceState = VoiceState.Idle,
                            statusMessage = standbyStatusMessage(),
                            awaitingTrigger = true,
                            partialTranscript = "",
                        )
                        wakeWordNotifier.clearListeningNotification()
                        restoreWakeWordPassive()
                        returnToStandby(delayMs = 0L)
                    }
                    StopSessionResult.NotHandled -> resumeListening(directAfter = false)
                }
            }
            else -> {
                // «para» / «cállate» / «basta»: cancelar pending y volver a standby
                processingJob = scope?.launch {
                    if (actionExecutor.hasPendingConfirmation()) {
                        actionExecutor.cancelPending()
                        conversationContext.clearPending()
                    }
                    actionExecutor.cancelVoiceNoteRecording()
                    speakOnly("Vale, paro.")
                    restoreWakeWordPassive()
                    returnToStandby(delayMs = 0L)
                }
            }
        }
    }

    private fun launchProcessUserSpeech(text: String) {
        processingJob?.cancel()
        processingJob = scope?.launch {
            processUserSpeech(text)
        }
    }

    private suspend fun handleSpeechError(message: String, isSilent: Boolean) {
        voiceCaptureInProgress = false
        if (isSpeaking || processingJob?.isActive == true) return

        val fallback = consumePendingWakeCommand()
        if (fallback.isNotBlank()) {
            dispatchCommand(fallback)
            return
        }

        // Como el modo Vosk estable: errores silenciosos no hablan «No te he oído»
        if (isSilent) {
            when {
                actionExecutor.hasPendingConfirmation() -> {
                    pendingSilentRetries++
                    if (pendingSilentRetries >= 2) {
                        pendingSilentRetries = 0
                        actionExecutor.cancelPending()
                        conversationContext.clearPending()
                        actionExecutor.cancelVoiceNoteRecording()
                        listenProfile = ListeningProfile.STANDBY
                        speakOnly("Vale, paro.")
                        restoreWakeWordPassive()
                        returnToStandby(delayMs = 0L)
                    } else {
                        resumePendingInput()
                    }
                }
                listenProfile == ListeningProfile.DIRECT_RESPONSE && silentRetries < 1 -> {
                    silentRetries++
                    startDirectListening(force = true, skipPause = true)
                }
                else -> {
                    silentRetries = 0
                    pendingSilentRetries = 0
                    // Wake vacío: no tip espontáneo (menos charla). Silencio → standby.
                    listenProfile = ListeningProfile.STANDBY
                    if (activeSessionTracker.isPausedForChat()) {
                        val kind = activeSessionTracker.snapshot()?.kind
                        if (kind == ActiveSessionKind.NAVIGATION ||
                            kind == ActiveSessionKind.ROUTE_REPLAY
                        ) {
                            navigationSessionManager.resumeFromChat()
                        } else {
                            activeSessionTracker.resumeFromChat()
                        }
                        enterNavigationPause()
                    } else {
                        returnToStandby(delayMs = 0L)
                    }
                }
            }
            return
        }

        pendingSilentRetries = 0
        if (message.isNotBlank()) {
            speakOnly(message)
        }
        resumeListening(directAfter = actionExecutor.hasPendingConfirmation())
    }

    private suspend fun processUserSpeech(text: String) {
        try {
            if (sleepModeController.isSleeping()) {
                softWaitToneEngine.stop()
                if (sleepModeController.isWakeFromSleepPhrase(text) ||
                    sleepModeController.isWakeFromSleepPhrase("lazaro $text")
                ) {
                    exitSleepMode()
                } else {
                    markState(
                        VoiceState.Idle,
                        "Modo dormir. Di Lázaro despierta.",
                        awaitingTrigger = true,
                    )
                    restoreWakeWordPassive()
                }
                return
            }
            if (sleepModeController.isSleepCommand(text)) {
                enterSleepMode()
                return
            }
            markState(
                voiceState = VoiceState.Processing,
                statusMessage = "Procesando…",
                awaitingTrigger = false,
                partialTranscript = text,
            )
            wakeWordNotifier.clearListeningNotification()

            val reply = geminiOrchestrator.handleUserMessage(text)
            resetCounters()
            val spoken = maybeAppendResumeOffer(reply).ifBlank {
                if (reply.actionTaken) "" else "No he entendido. Repite, por favor."
            }
            conversationContext.recordTurn(
                userMessage = text,
                assistantMessage = spoken,
                sessionMarker = activeSessionTracker.historyMarker(),
            )
            if (actionExecutor.hasPendingConfirmation()) {
                conversationContext.recordPending(
                    hint = actionExecutor.getPendingHint(),
                    prompt = actionExecutor.getLastPromptText().ifBlank { spoken },
                )
            } else if (!reply.skipAutoLearn) {
                conversationContext.clearPending()
            }
            _uiState.update { it.copy(lastResponse = spoken) }

            val needsDirectInput = actionExecutor.hasPendingConfirmation()
            val keepConversationOpen = SamsungVoiceCompat.allowsConversationAutoListen() &&
                !needsDirectInput &&
                !reply.suspendListening &&
                spoken.isNotBlank()

            // Respuesta vacía (p. ej. interrupt): volver a standby sin tratarlo como fallo TTS.
            if (spoken.isBlank()) {
                restoreWakeWordPassive()
                returnToStandby(delayMs = 0L)
                return
            }

            val completed = speakOnly(spoken)
            if (!isActive) return
            if (!completed) {
                actionExecutor.cancelVoiceNoteRecording()
                if (userAbortDuringSpeech.getAndSet(false)) {
                    // «para» durante lectura (p. ej. WhatsApp): salir del pending y no reabrir escucha
                    if (actionExecutor.hasPendingConfirmation()) {
                        actionExecutor.cancelPending()
                        conversationContext.clearPending()
                    }
                    speakOnly("Vale, paro.")
                }
                restoreWakeWordPassive()
                returnToStandby(delayMs = 0L)
                return
            }
            userAbortDuringSpeech.set(false)

            // Tras anunciar «Grabando…», abrir mic de MediaRecorder (sin STT).
            if (maybeBeginVoiceNoteCapture()) {
                markState(
                    VoiceState.Listening,
                    "Grabando mensaje de voz. Botón centro para enviar.",
                    awaitingTrigger = false,
                )
                return
            }

            val resumedNav = reply.actionTaken &&
                spoken.contains("Seguimos hacia", ignoreCase = true) &&
                activeSessionTracker.snapshot()?.phase == ActiveSessionPhase.RUNNING

            if (resumedNav) {
                conversationWindowJob?.cancel()
                enterNavigationPause()
            } else if (reply.suspendListening) {
                conversationWindowJob?.cancel()
                val mapsOk = if (actionExecutor.hasDeferredMapsLaunch()) {
                    actionExecutor.runDeferredMapsLaunch()
                } else {
                    true
                }
                val navAlready =
                    navigationSessionManager.isNavigationActive() ||
                        pathGuideController.currentMode() == PathGuideMode.NAVEGACION ||
                        pathGuideController.currentMode() == PathGuideMode.RUTA
                if (mapsOk || navAlready) {
                    val label = extractNavigationLabel(text, spoken)
                    val routeReplay = pathGuideController.currentMode() == PathGuideMode.RUTA
                    if (!navigationSessionManager.isNavigationActive() ||
                        !activeSessionTracker.hasActiveSession()
                    ) {
                        val navTarget = actionExecutor.consumePendingNavigationTarget()
                            ?: NavigationTarget(label = label)
                        navigationSessionManager.startSession(
                            label = label,
                            routeReplay = routeReplay,
                            target = navTarget,
                        )
                    } else {
                        activeSessionTracker.updateLabel(label)
                        activeSessionTracker.resumeFromChat()
                    }
                    scope?.launch {
                        val mode = pathGuideController.currentMode()
                        if (mode == PathGuideMode.RUTA) return@launch
                        if (mode != PathGuideMode.NAVEGACION) {
                            val camOk = pathGuideController.start(PathGuideMode.NAVEGACION)
                            if (!camOk) {
                                speakOnly(
                                    "No pude abrir la cámara para guiarte. " +
                                        "Comprueba el permiso de cámara e inténtalo otra vez.",
                                )
                            }
                        }
                    }
                    enterNavigationPause()
                } else {
                    speakOnly(
                        "No pude iniciar la guía. Comprueba ubicación y cámara e inténtalo otra vez.",
                    )
                    openConversationWindow()
                    resumeListening(directAfter = true)
                }
            } else if (needsDirectInput) {
                conversationWindowJob?.cancel()
                listenProfile = ListeningProfile.DIRECT_RESPONSE
                startDirectListening(force = true)
            } else if (keepConversationOpen) {
                openConversationWindow()
                scheduleListen(delayMs = SamsungVoiceCompat.postSpeechDelayMs)
            } else {
                conversationWindowJob?.cancel()
                if (activeSessionTracker.isPausedForChat()) {
                    // Tras orden lateral sin confirmación pendiente: volver a wake sobre la sesión
                    enterNavigationPause()
                } else {
                    restoreWakeWordPassive()
                    returnToStandby()
                }
            }

            // Auto-learn: permitir tras acciones útiles salvo nav suspend / pending / resume prompt
            val awaitingResume = activeSessionTracker.snapshot()?.awaitingResumePrompt == true
            if (!reply.skipAutoLearn && !reply.suspendListening && !awaitingResume) {
                if (!reply.actionTaken || looksLikeMemorableAction(text)) {
                    scope?.launch { backgroundMaybeLearn(text, spoken) }
                }
            }
        } catch (e: CancellationException) {
            softWaitToneEngine.stop()
        } catch (e: Exception) {
            softWaitToneEngine.stop()
            speakOnly("Algo falló. Sigo aquí.")
            if (actionExecutor.hasPendingConfirmation()) {
                resumePendingInput()
            } else {
                restoreWakeWordPassive()
                returnToStandby()
            }
        } finally {
            softWaitToneEngine.stop()
            processingJob = null
        }
    }

    private suspend fun maybeAppendResumeOffer(reply: AssistantReply): String {
        if (reply.suspendListening) return reply.spokenText
        if (actionExecutor.hasPendingConfirmation()) return reply.spokenText
        if (!activeSessionTracker.isPausedForChat()) return reply.spokenText
        val base = reply.spokenText.trim()
        if (base.length > RESUME_OFFER_MAX_BASE_CHARS) return base
        if (base.contains("¿Seguimos", ignoreCase = true) ||
            base.contains("reanud", ignoreCase = true) ||
            base.contains("seguimos", ignoreCase = true)
        ) {
            return base
        }
        val offer = proactiveSuggestionEngine.suggestionAfterSideOrder() ?: return base
        actionExecutor.setPendingResumeSession(offer)
        return "$base $offer"
    }

    private fun extractNavigationLabel(userText: String, spoken: String): String {
        val fromUser = Regex(
            """(?i)(?:a|hacia|hasta)\s+(.+)$""",
        ).find(userText.trim())?.groupValues?.getOrNull(1)?.trim()
        if (!fromUser.isNullOrBlank() && fromUser.length < 40) return fromUser
        val fromSpeech = Regex(
            """(?i)(?:hacia|hasta|a)\s+([^?.!]+)""",
        ).find(spoken)?.groupValues?.getOrNull(1)?.trim()
        if (!fromSpeech.isNullOrBlank() && fromSpeech.length < 40) return fromSpeech
        return activeSessionTracker.snapshot()?.label ?: "destino"
    }

    private fun looksLikeMemorableAction(userText: String): Boolean {
        val t = userText.lowercase(Locale.getDefault())
        return t.contains("casa") || t.contains("guarda") || t.contains("recuerda") ||
            t.contains("mi ") || t.contains("farmacia") || t.contains("trabajo")
    }

    private fun syncActiveSessionFromHardware() {
        if (activeSessionTracker.hasActiveSession()) return
        when (pathGuideController.currentMode()) {
            PathGuideMode.NAVEGACION ->
                activeSessionTracker.start(ActiveSessionKind.NAVIGATION, "destino")
            PathGuideMode.RUTA ->
                activeSessionTracker.start(ActiveSessionKind.ROUTE_REPLAY, "ruta")
            PathGuideMode.PASEO ->
                activeSessionTracker.start(ActiveSessionKind.WALK, "paseo")
            PathGuideMode.GRABANDO ->
                activeSessionTracker.start(ActiveSessionKind.RECORDING, "ruta nueva")
            else -> {
                if (routeRecorderController.isRecording()) {
                    activeSessionTracker.start(ActiveSessionKind.RECORDING, "ruta nueva")
                } else if (navigationGuidanceMonitor.isNavigationActive()) {
                    activeSessionTracker.start(ActiveSessionKind.NAVIGATION, "destino")
                }
            }
        }
    }

    private suspend fun backgroundMaybeLearn(userText: String, assistantText: String) {
        val latest = memoryRepository.getLatestProposal()
        if (latest != null) {
            val ageMs = System.currentTimeMillis() - latest.createdAt
            if (ageMs < STALE_PROPOSAL_MS) return
            memoryRepository.rejectLatestProposal()
        }
        if (actionExecutor.hasPendingConfirmation()) return
        if (activeSessionTracker.snapshot()?.awaitingResumePrompt == true) return

        val context = memoryContextBuilder.buildContextBlock()
        val proposal = memoryExtractor.extractFromConversation(userText, assistantText, context)
            ?: return

        memoryRepository.saveProposal(proposal)
        val question = memoryExtractor.buildProposalQuestion(proposal)
        if (!isActive || isSpeaking || processingJob?.isActive == true) return

        actionExecutor.setAwaitingMemoryConfirmation(question)
        conversationContext.recordPending("confirmar guardar en memoria", question)
        _uiState.update { it.copy(lastResponse = question) }
        speakOnly(question)
        openConversationWindow()
        resumePendingInput()
    }

    private suspend fun finishVoiceNoteFromCane() {
        if (!actionExecutor.isVoiceNoteRecording()) return
        wakeWordController.pauseForCommand()
        val result = actionExecutor.finishVoiceNoteRecording()
        val msg = when (result) {
            is ActionResult.Success -> result.message
            is ActionResult.Error -> result.message
            is ActionResult.NeedsConfirmation -> result.prompt
        }
        if (msg.isNotBlank()) {
            speakOnly(msg)
        }
        restoreWakeWordPassive()
        returnToStandby(delayMs = 0L)
    }

    private fun maybeBeginVoiceNoteCapture(): Boolean {
        val args = actionExecutor.consumeArmedVoiceNoteStart() ?: return false
        val sc = scope ?: return false
        wakeWordController.pauseForCommand()
        speechRecognitionManager.stopListening()
        softWaitToneEngine.stop()
        val ok = actionExecutor.beginArmedVoiceNoteCapture(sc, args)
        if (!ok) {
            scope?.launch {
                speakOnly("No pude empezar la grabación. Revisa el micrófono e inténtalo de nuevo.")
                returnToStandby(delayMs = 0L)
            }
            return false
        }
        // Auto-envío al llegar al tope de tiempo
        sc.launch {
            delay(io.lazaro.messaging.WhatsAppVoiceNoteAction.MAX_RECORD_MS + 400L)
            if (!isActive || !actionExecutor.isVoiceNoteRecording()) return@launch
            finishVoiceNoteFromCane()
        }
        return true
    }

    private fun scheduleConversationListen() {
        if (!isActive || isSpeaking || listeningSuspended) return
        if (listenProfile != ListeningProfile.DIRECT_RESPONSE &&
            !actionExecutor.hasPendingConfirmation()
        ) {
            return
        }
        startDirectListening(force = true)
    }

    private fun openConversationWindow() {
        conversationWindowJob?.cancel()
        listenProfile = ListeningProfile.DIRECT_RESPONSE
        conversationWindowJob = scope?.launch {
            delay(CONVERSATION_WINDOW_MS)
            if (!isActive || isSpeaking || actionExecutor.hasPendingConfirmation()) return@launch
            listenProfile = ListeningProfile.STANDBY
            restoreWakeWordPassive()
            returnToStandby(delayMs = 0L)
        }
    }

    private suspend fun speakOnly(message: String): Boolean {
        if (!isActive) return false
        if (sleepModeController.isSleeping()) return false

        softWaitToneEngine.stop()
        cancelScheduledListen()
        speechRecognitionManager.stopListening()
        voiceCaptureInProgress = false

        if (message.isBlank()) return true

        userAbortDuringSpeech.set(false)
        isSpeaking = true
        markState(voiceState = VoiceState.Speaking, statusMessage = message)

        // Barge-in: «para» corta TTS y marca aborto (el caller cancela pending).
        val bargeInJob = scope?.launch {
            delay(BARGE_IN_ARM_MS)
            if (!isActive || !isSpeaking || sleepModeController.isSleeping()) return@launch
            speechRecognitionManager.startDirectResponseListening(
                onResult = { text ->
                    if (!isSpeaking) return@startDirectResponseListening
                    if (contextIntentDetector.isInterruptCommand(text) ||
                        contextIntentDetector.isCancelPhrase(text)
                    ) {
                        userAbortDuringSpeech.set(true)
                        textToSpeechManager.stop()
                    }
                },
                onError = { _, _ -> },
            )
        }

        val finished = try {
            textToSpeechManager.speak(message)
        } finally {
            bargeInJob?.cancel()
            speechRecognitionManager.stopListening()
            isSpeaking = false
        }
        if (finished && isActive && !sleepModeController.isSleeping()) {
            delay(SamsungVoiceCompat.postSpeechDelayMs)
        }
        return finished && isActive
    }

    private fun enterNavigationPause() {
        softWaitToneEngine.stop()
        cancelScheduledListen()
        speechRecognitionManager.stopListening()
        voiceCaptureInProgress = false
        restoreWakeWordPassive()
        resetCounters()
        listenProfile = ListeningProfile.STANDBY
        listeningSuspended = true
        val label = activeSessionTracker.snapshot()?.label ?: "destino"
        markState(
            voiceState = VoiceState.Idle,
            statusMessage = "Navegando a $label. Coloca el teléfono en el arnés; la cámara guía con pitidos. Di Lazaro para hablar.",
            awaitingTrigger = true,
        )

        navigationPauseJob?.cancel()
        navigationPauseJob = scope?.launch {
            delay(NAVIGATION_PAUSE_MS)
            if (isActive) {
                resumeAfterNavigationPause()
            }
        }
    }

    private suspend fun resumeAfterNavigationPause() {
        // Timeout largo: preguntar una vez si continuar; no matar la sesión en silencio.
        if (activeSessionTracker.hasActiveSession() || navigationSessionManager.isNavigationActive()) {
            listeningSuspended = false
            navigationPauseJob?.cancel()
            val question = activeSessionTracker.snapshot()?.resumeQuestion()
                ?: "¿Seguimos con la navegación o la cancelo?"
            actionExecutor.setPendingResumeSession(question)
            conversationContext.recordPending("reanudar o cancelar navegación", question)
            speakOnly(question)
            openConversationWindow()
            resumePendingInput()
            // Segunda espera: si no responde, entonces sí cerrar
            navigationPauseJob = scope?.launch {
                delay(NAVIGATION_FOLLOWUP_MS)
                if (!isActive) return@launch
                if (actionExecutor.hasPendingConfirmation() &&
                    actionExecutor.getPendingHint().contains("reanudar", ignoreCase = true)
                ) {
                    actionExecutor.cancelPending()
                    listeningSuspended = false
                    navigationSessionManager.endSession(speakConfirmation = true)
                    returnToStandby(delayMs = 0L)
                }
            }
            return
        }
        listeningSuspended = false
        navigationPauseJob?.cancel()
        returnToStandby(delayMs = 0L)
    }

    private fun markState(
        voiceState: VoiceState,
        statusMessage: String,
        awaitingTrigger: Boolean = _uiState.value.awaitingTrigger,
        partialTranscript: String = _uiState.value.partialTranscript,
    ) {
        lastStateChangeMs = System.currentTimeMillis()
        when (voiceState) {
            VoiceState.Processing -> {
                speechRecognitionManager.stopListening()
                voiceCaptureInProgress = false
                if (sleepModeController.isSleeping()) {
                    softWaitToneEngine.stop()
                } else {
                    // Tono de espera + wake activo: «Lázaro» cancela la acción en curso.
                    softWaitToneEngine.startDelayed()
                    restoreWakeWordPassive()
                }
            }
            VoiceState.Speaking -> {
                softWaitToneEngine.stop()
                // Durante TTS no dejar Vosk (evita pelea de mic con barge-in).
                wakeWordController.pauseForCommand()
            }
            VoiceState.Listening -> softWaitToneEngine.stop()
            VoiceState.Idle,
            VoiceState.Error,
            -> softWaitToneEngine.stop()
        }
        _uiState.update {
            it.copy(
                voiceState = voiceState,
                statusMessage = statusMessage,
                awaitingTrigger = awaitingTrigger,
                partialTranscript = partialTranscript,
                audioLevel = when {
                    voiceState == VoiceState.Speaking -> 0f
                    speechRecognitionManager.isActive() -> it.audioLevel
                    else -> 0f
                },
            )
        }
    }

    private fun startWatchdog() {
        stopWatchdog()
        watchdogJob = scope?.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                if (!isActive) break
                recoverIfStuck()
            }
        }
    }

    private fun stopWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    /** Fuerza wake pasivo Google (prioridad máxima tras operaciones / standby). */
    private fun restoreWakeWordPassive() {
        wakeWordController.ensurePassiveListening()
    }

    private fun recoverIfStuck() {
        val state = _uiState.value.voiceState
        val elapsed = System.currentTimeMillis() - lastStateChangeMs
        val wakeStatus = _uiState.value.wakeWordStatus
        val awaiting = _uiState.value.awaitingTrigger

        if (sleepModeController.isSleeping()) {
            softWaitToneEngine.stop()
            if (state != VoiceState.Idle || !awaiting) {
                speechRecognitionManager.stopListening()
                voiceCaptureInProgress = false
                processingJob?.cancel()
                processingJob = null
                isSpeaking = false
                markState(
                    VoiceState.Idle,
                    "Modo dormir. Di Lázaro despierta.",
                    awaitingTrigger = true,
                )
            }
            // Solo asegurar Vosk en modo «Lázaro despierta»; nunca abrir Google STT.
            if (awaiting &&
                !isSpeaking &&
                !voiceCaptureInProgress &&
                (wakeStatus == WakeWordStatus.PAUSED ||
                    wakeStatus == WakeWordStatus.ERROR ||
                    wakeStatus == WakeWordStatus.OFF) &&
                elapsed > STUCK_WAKE_MS
            ) {
                wakeWordController.ensurePassiveListening()
            }
            return
        }

        // Nota de voz WhatsApp: si el timeout del job ya venció, enviar
        if (actionExecutor.isVoiceNoteRecording() && actionExecutor.isVoiceNoteTimedOut()) {
            scope?.launch { finishVoiceNoteFromCane() }
            return
        }

        when {
            // Procesando colgado (GPS, Gemini, etc.): cancelar aunque el job siga «activo»
            state == VoiceState.Processing && elapsed > STUCK_PROCESSING_MS -> {
                softWaitToneEngine.stop()
                processingJob?.cancel()
                processingJob = null
                isSpeaking = false
                markState(
                    VoiceState.Idle,
                    "Cancelado: tardaba demasiado. Di Lázaro.",
                    awaitingTrigger = true,
                )
                restoreWakeWordPassive()
                resumeListening(directAfter = false)
            }
            // Speaking sin TTS real (p. ej. acción antigua mal marcada): recuperar
            state == VoiceState.Speaking &&
                !textToSpeechManager.isSpeaking.value &&
                elapsed > STUCK_SPEAKING_MS -> {
                isSpeaking = false
                softWaitToneEngine.stop()
                restoreWakeWordPassive()
                resumeListening(directAfter = actionExecutor.hasPendingConfirmation())
            }
            state == VoiceState.Listening &&
                actionExecutor.hasPendingConfirmation() &&
                !speechRecognitionManager.isActive() &&
                processingJob?.isActive != true &&
                !isSpeaking &&
                !listeningSuspended &&
                elapsed > 2_500L -> {
                startDirectListening(force = true, skipPause = true)
            }
            // Tras «Lázaro»: no ignorar STT muerto solo porque voiceCaptureInProgress siga true.
            state == VoiceState.Listening &&
                processingJob?.isActive != true &&
                !isSpeaking &&
                !listeningSuspended &&
                elapsed > 2_500L &&
                !speechRecognitionManager.isActive() -> {
                if (elapsed > STUCK_LISTENING_MS) {
                    voiceCaptureInProgress = false
                    pendingWakeCommand = ""
                    resumeListening(directAfter = actionExecutor.hasPendingConfirmation())
                } else {
                    startDirectListening(force = true, skipPause = true)
                }
            }
            state == VoiceState.Listening &&
                speechRecognitionManager.isActive() &&
                processingJob?.isActive != true &&
                !isSpeaking &&
                !listeningSuspended &&
                elapsed > STUCK_LISTENING_MS -> {
                voiceCaptureInProgress = false
                pendingWakeCommand = ""
                speechRecognitionManager.stopListening()
                resumeListening(directAfter = actionExecutor.hasPendingConfirmation())
            }
            // Vosk caído en standby
            awaiting &&
                state == VoiceState.Idle &&
                !isSpeaking &&
                !voiceCaptureInProgress &&
                processingJob?.isActive != true &&
                (wakeStatus == WakeWordStatus.PAUSED ||
                    wakeStatus == WakeWordStatus.ERROR ||
                    wakeStatus == WakeWordStatus.OFF) &&
                elapsed > STUCK_WAKE_MS -> {
                if (wakeStatus == WakeWordStatus.ERROR) {
                    scope?.launch { blindStatusSpeaker.noteMicOrWakeFailure() }
                }
                wakeWordController.ensurePassiveListening()
                if (wakeStatus == WakeWordStatus.ERROR || wakeStatus == WakeWordStatus.OFF) {
                    scope?.launch {
                        delay(1_200L)
                        if (isActive &&
                            !sleepModeController.isSleeping() &&
                            _uiState.value.wakeWordStatus == WakeWordStatus.ACTIVE
                        ) {
                            blindStatusSpeaker.resetMicFailStreak()
                            blindStatusSpeaker.announceReady()
                        }
                    }
                }
            }
            navigationSessionManager.isNavigationActive() &&
                awaiting &&
                state == VoiceState.Idle &&
                wakeStatus != WakeWordStatus.ACTIVE &&
                wakeStatus != WakeWordStatus.STARTING &&
                !isSpeaking &&
                !voiceCaptureInProgress &&
                processingJob?.isActive != true -> {
                wakeWordController.ensurePassiveListening()
            }
        }
    }

    companion object {
        private const val WATCHDOG_INTERVAL_MS = 5_000L
        private const val STUCK_PROCESSING_MS = 25_000L
        private const val STUCK_SPEAKING_MS = 45_000L
        private const val STUCK_LISTENING_MS = 22_000L
        private const val STUCK_WAKE_MS = 4_000L
        private const val CONVERSATION_WINDOW_MS = 50_000L
        private const val NAVIGATION_PAUSE_MS = 45 * 60 * 1000L
        private const val NAVIGATION_FOLLOWUP_MS = 90_000L
        private const val STALE_PROPOSAL_MS = 10 * 60 * 1000L
        private const val WAKE_DEBOUNCE_MS = 2_000L
        private const val MIC_HANDOFF_DELAY_MS = 550L
        /** Espera antes de abrir mic para «para» durante TTS (evita eco inmediato). */
        private const val BARGE_IN_ARM_MS = 700L
        /** No concatenar «¿Seguimos…?» si la respuesta ya es larga. */
        private const val RESUME_OFFER_MAX_BASE_CHARS = 120
    }
}
