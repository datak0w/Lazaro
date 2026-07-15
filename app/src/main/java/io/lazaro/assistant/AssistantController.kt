package io.lazaro.assistant

import io.lazaro.actions.ActionExecutor
import io.lazaro.actions.ActionResult
import io.lazaro.audiobook.BookReaderAction
import io.lazaro.ai.GeminiOrchestrator
import io.lazaro.ai.MemoryExtractor
import io.lazaro.memory.MemoryContextBuilder
import io.lazaro.memory.MemoryRepository
import io.lazaro.navigation.NavigationGuidanceMonitor
import io.lazaro.navigation.NavigationSessionManager
import io.lazaro.pathguide.PathGuideController
import io.lazaro.pathguide.PathGuideMode
import io.lazaro.voice.ListeningProfile
import io.lazaro.voice.SamsungVoiceCompat
import io.lazaro.voice.SpeechRecognitionManager
import io.lazaro.voice.TextToSpeechManager
import io.lazaro.voice.VoiceState
import io.lazaro.voice.WakeWordController
import io.lazaro.voice.WakeWordDetector
import io.lazaro.voice.WakeWordMatch
import io.lazaro.voice.WakeWordNotifier
import io.lazaro.voice.WakeWordStatus
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
import javax.inject.Inject
import javax.inject.Singleton

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
    private val stopActiveSessionHandler: StopActiveSessionHandler,
    private val pathGuideController: PathGuideController,
    private val wakeWordController: WakeWordController,
    private val wakeWordNotifier: WakeWordNotifier,
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
    private var resumeListeningJob: Job? = null
    private var processingJob: Job? = null
    private var watchdogJob: Job? = null
    private var navigationPauseJob: Job? = null
    private var conversationWindowJob: Job? = null
    private var listeningSuspended = false
    private var voiceCaptureInProgress = false
    private var lastStateChangeMs = System.currentTimeMillis()

    fun bind(scope: CoroutineScope) {
        this.scope = scope
        wakeWordController.bind(scope, ::onWakeWordDetected)
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
        wakeWordController.start()
        returnToStandby()
    }

    fun stopAssistant() {
        isActive = false
        listeningSuspended = false
        navigationPauseJob?.cancel()
        conversationWindowJob?.cancel()
        forceStopOutput()
        wakeWordController.stop()
        navigationGuidanceMonitor.stopNavigation()
        pathGuideController.stop()
        resetCounters()
        stopWatchdog()
        speechRecognitionManager.shutdown()
        markState(VoiceState.Idle, "Asistente detenido.", awaitingTrigger = true)
    }

    fun interruptAndListen() {
        if (!isActive) return
        listeningSuspended = false
        navigationPauseJob?.cancel()
        processingJob?.cancel()
        processingJob = null
        forceStopOutput()
        resetCounters()
        listenProfile = ListeningProfile.DIRECT_RESPONSE
        val hint = if (actionExecutor.hasPendingConfirmation()) {
            "Responde, repíteme las opciones, o di cancela."
        } else {
            "Te escucho."
        }
        markState(VoiceState.Listening, hint, awaitingTrigger = false)
        startDirectListening()
    }

    private fun resumeListening(directAfter: Boolean = false) {
        if (!isActive || isSpeaking) return
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
        cancelScheduledListen()
        listenProfile = ListeningProfile.DIRECT_RESPONSE
        speechRecognitionManager.stopListening()

        resumeListeningJob = scope?.launch {
            delay(SamsungVoiceCompat.pendingListenRetryMs)
            if (!isActive || isSpeaking || listeningSuspended) return@launch
            if (!actionExecutor.hasPendingConfirmation()) {
                returnToStandby()
                return@launch
            }
            startDirectListening(force = true)
        }
    }

    private fun returnToStandby(delayMs: Long = SamsungVoiceCompat.postSpeechDelayMs) {
        if (!isActive || listeningSuspended) return
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
            speechRecognitionManager.stopListening()
            markState(
                voiceState = VoiceState.Idle,
                statusMessage = standbyStatusMessage(),
                awaitingTrigger = true,
                partialTranscript = "",
            )
        }
    }

    private fun forceStopOutput() {
        isSpeaking = false
        voiceCaptureInProgress = false
        cancelScheduledListen()
        processingJob?.cancel()
        processingJob = null
        textToSpeechManager.stop()
        bookReaderAction.stopPlayback()
        speechRecognitionManager.stopListening()
    }

    private fun resetCounters() {
        silentRetries = 0
    }

    private fun standbyStatusMessage(): String {
        return SamsungVoiceCompat.samsungResumeHint()
            ?: "Listo. Di Lazaro, toca la pantalla o pulsa el botón del bastón."
    }

    private fun onWakeWordDetected() {
        if (!isActive || isSpeaking) return
        if (voiceCaptureInProgress && !navigationSessionManager.isNavigationActive()) return
        wakeWordNotifier.playActivationSound()
        wakeWordController.pauseForCommand()
        listeningSuspended = false
        navigationPauseJob?.cancel()
        processingJob?.cancel()
        processingJob = null
        forceStopOutput()
        resetCounters()
        listenProfile = ListeningProfile.DIRECT_RESPONSE
        markState(VoiceState.Listening, "Te escucho.", awaitingTrigger = false)
        startDirectListening(force = true, skipPause = true)
    }

    private fun startDirectListening(force: Boolean = false, skipPause: Boolean = false) {
        if (!isActive || isSpeaking || listeningSuspended) return
        if (!force && speechRecognitionManager.isActive()) return

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

    private fun resolveCommandText(text: String, wakeMatch: WakeWordMatch? = null): String {
        val match = wakeMatch ?: WakeWordDetector.parse(text)
        return when {
            match.detected && match.command.isNotBlank() -> match.command
            else -> text
        }
    }

    private suspend fun dispatchCommand(command: String) {
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

    private suspend fun handleSpeechResult(rawText: String) {
        voiceCaptureInProgress = false
        silentRetries = 0

        val text = rawText.trim()
        if (text.isBlank()) {
            releaseWakeWordAfterCommand()
            returnToStandby(delayMs = 0L)
            return
        }

        if (contextIntentDetector.isInterruptCommand(text) ||
            contextIntentDetector.isNavigationStopPhrase(text)
        ) {
            handleInterruptCommand(text)
            return
        }

        val wakeMatch = WakeWordDetector.parse(text)
        if (wakeMatch.detected && wakeMatch.command.isBlank()) {
            speakOnly("Te escucho.")
            listenProfile = ListeningProfile.DIRECT_RESPONSE
            startDirectListening(force = true)
            return
        }

        val command = resolveCommandText(text, wakeMatch)
        dispatchCommand(command)
    }

    private suspend fun handleInterruptCommand(rawText: String) {
        processingJob?.cancel()
        processingJob = null
        speechRecognitionManager.stopListening()
        isSpeaking = false
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
                        markState(
                            voiceState = VoiceState.Idle,
                            statusMessage = standbyStatusMessage(),
                            awaitingTrigger = true,
                            partialTranscript = "",
                        )
                        wakeWordController.ensurePassiveListening()
                        returnToStandby(delayMs = 0L)
                    }
                    StopSessionResult.NotHandled -> resumeListening(directAfter = false)
                }
            }
            else -> {
                textToSpeechManager.stop()
                bookReaderAction.stopPlayback()
                resumeListening(directAfter = false)
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

        if (isSilent) {
            when {
                actionExecutor.hasPendingConfirmation() -> resumePendingInput()
                resolveListenProfile() == ListeningProfile.DIRECT_RESPONSE -> {
                    silentRetries = 0
                    listenProfile = ListeningProfile.STANDBY
                    releaseWakeWordAfterCommand()
                    scheduleListen(delayMs = SamsungVoiceCompat.returnToPassiveDelayMs)
                }
                else -> {
                    releaseWakeWordAfterCommand()
                    returnToStandby(delayMs = 0L)
                }
            }
            return
        }
        if (message.isNotBlank()) {
            speakOnly(message)
        }
        resumeListening(directAfter = actionExecutor.hasPendingConfirmation())
    }

    private suspend fun processUserSpeech(text: String) {
        try {
            markState(
                voiceState = VoiceState.Processing,
                statusMessage = "Procesando…",
                awaitingTrigger = false,
                partialTranscript = text,
            )

            val reply = geminiOrchestrator.handleUserMessage(text)
            resetCounters()
            conversationContext.recordTurn(text, reply.spokenText)
            if (actionExecutor.hasPendingConfirmation()) {
                conversationContext.recordPending(
                    hint = actionExecutor.getPendingHint(),
                    prompt = actionExecutor.getLastPromptText().ifBlank { reply.spokenText },
                )
            } else if (!reply.skipAutoLearn) {
                conversationContext.clearPending()
            }
            _uiState.update { it.copy(lastResponse = reply.spokenText) }

            val needsDirectInput = actionExecutor.hasPendingConfirmation()
            val keepConversationOpen = SamsungVoiceCompat.allowsConversationAutoListen() &&
                !needsDirectInput &&
                !reply.suspendListening &&
                reply.spokenText.isNotBlank()

            speakOnly(reply.spokenText)

            if (!isActive) return

            if (reply.suspendListening) {
                conversationWindowJob?.cancel()
                val mapsLaunched = actionExecutor.runDeferredMapsLaunch()
                if (mapsLaunched) {
                    navigationSessionManager.startSession()
                    scope?.launch {
                        if (pathGuideController.currentMode() != PathGuideMode.RUTA) {
                            pathGuideController.start(PathGuideMode.NAVEGACION)
                        }
                    }
                    enterNavigationPause()
                } else {
                    speakOnly(
                        "No pude abrir Google Maps. Comprueba que esté instalado y vuelve a intentarlo.",
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
                releaseWakeWordAfterCommand()
                returnToStandby()
            }

            if (!reply.skipAutoLearn && !reply.actionTaken && !reply.suspendListening) {
                scope?.launch { backgroundMaybeLearn(text, reply.spokenText) }
            }
        } catch (e: CancellationException) {
            // Interrupción: handleInterruptCommand o interruptAndListen retoman.
        } catch (e: Exception) {
            speakOnly("Algo falló. Sigo aquí.")
            if (actionExecutor.hasPendingConfirmation()) {
                resumePendingInput()
            } else {
                returnToStandby()
            }
        } finally {
            processingJob = null
        }
    }

    private suspend fun backgroundMaybeLearn(userText: String, assistantText: String) {
        if (memoryRepository.getLatestProposal() != null) return
        if (actionExecutor.hasPendingConfirmation()) return

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
            returnToStandby(delayMs = 0L)
        }
    }

    private suspend fun speakOnly(message: String) {
        if (!isActive) return

        cancelScheduledListen()
        speechRecognitionManager.stopListening()
        voiceCaptureInProgress = false

        if (message.isBlank()) return

        isSpeaking = true
        markState(voiceState = VoiceState.Speaking, statusMessage = message)
        try {
            textToSpeechManager.speak(message)
        } finally {
            isSpeaking = false
            delay(SamsungVoiceCompat.postSpeechDelayMs)
        }
    }

    private fun enterNavigationPause() {
        cancelScheduledListen()
        speechRecognitionManager.stopListening()
        voiceCaptureInProgress = false
        wakeWordController.releaseAfterCommand()
        scope?.launch {
            delay(600L)
            wakeWordController.ensurePassiveListening()
        }
        resetCounters()
        listenProfile = ListeningProfile.STANDBY
        markState(
            voiceState = VoiceState.Idle,
            statusMessage = "Navegando con Maps. Di Lazaro o usa el bastón; la cámara guía con pitidos.",
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
        listeningSuspended = false
        navigationPauseJob?.cancel()
        navigationSessionManager.endSession(speakConfirmation = false)
        returnToStandby(delayMs = 0L)
    }

    private fun markState(
        voiceState: VoiceState,
        statusMessage: String,
        awaitingTrigger: Boolean = _uiState.value.awaitingTrigger,
        partialTranscript: String = _uiState.value.partialTranscript,
    ) {
        lastStateChangeMs = System.currentTimeMillis()
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

    private fun releaseWakeWordAfterCommand() {
        wakeWordController.releaseAfterCommand()
    }

    private fun recoverIfStuck() {
        val state = _uiState.value.voiceState
        val elapsed = System.currentTimeMillis() - lastStateChangeMs

        when {
            state == VoiceState.Processing && processingJob?.isActive != true && elapsed > STUCK_PROCESSING_MS -> {
                processingJob = null
                isSpeaking = false
                markState(VoiceState.Idle, "Recuperado.", awaitingTrigger = !actionExecutor.hasPendingConfirmation())
                resumeListening(directAfter = actionExecutor.hasPendingConfirmation())
            }
            state == VoiceState.Speaking && !textToSpeechManager.isSpeaking.value && elapsed > STUCK_SPEAKING_MS -> {
                isSpeaking = false
                resumeListening(directAfter = actionExecutor.hasPendingConfirmation())
            }
            state == VoiceState.Listening &&
                actionExecutor.hasPendingConfirmation() &&
                !speechRecognitionManager.isActive() &&
                processingJob?.isActive != true &&
                !isSpeaking &&
                !listeningSuspended &&
                elapsed > 2_500L -> {
                startDirectListening(force = true)
            }
            state == VoiceState.Listening &&
                !speechRecognitionManager.isActive() &&
                resumeListeningJob?.isActive != true &&
                processingJob?.isActive != true &&
                !isSpeaking &&
                !listeningSuspended &&
                elapsed > STUCK_LISTENING_MS -> {
                resumeListening(directAfter = actionExecutor.hasPendingConfirmation())
            }
            navigationSessionManager.isNavigationActive() &&
                _uiState.value.wakeWordStatus != WakeWordStatus.ACTIVE &&
                _uiState.value.wakeWordStatus != WakeWordStatus.STARTING &&
                _uiState.value.wakeWordStatus != WakeWordStatus.PAUSED &&
                !isSpeaking &&
                !voiceCaptureInProgress -> {
                wakeWordController.ensurePassiveListening()
            }
        }
    }

    companion object {
        private const val WATCHDOG_INTERVAL_MS = 8_000L
        private const val STUCK_PROCESSING_MS = 25_000L
        private const val STUCK_SPEAKING_MS = 12_000L
        private const val STUCK_LISTENING_MS = 22_000L
        private const val CONVERSATION_WINDOW_MS = 50_000L
        private const val NAVIGATION_PAUSE_MS = 45 * 60 * 1000L
    }
}
