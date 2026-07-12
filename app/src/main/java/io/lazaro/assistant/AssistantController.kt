package io.lazaro.assistant

import io.lazaro.actions.ActionExecutor
import io.lazaro.audiobook.BookReaderAction
import io.lazaro.ai.GeminiOrchestrator
import io.lazaro.ai.MemoryExtractor
import io.lazaro.memory.MemoryContextBuilder
import io.lazaro.memory.MemoryRepository
import io.lazaro.navigation.NavigationGuidanceMonitor
import io.lazaro.voice.ListeningProfile
import io.lazaro.voice.SpeechRecognitionManager
import io.lazaro.voice.TextToSpeechManager
import io.lazaro.voice.VoiceState
import io.lazaro.voice.WakeWordDetector
import io.lazaro.voice.WakeWordNotifier
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
    val awaitingWakeWord: Boolean = true,
    val audioLevel: Float = 0f,
)

@Singleton
class AssistantController @Inject constructor(
    private val speechRecognitionManager: SpeechRecognitionManager,
    private val textToSpeechManager: TextToSpeechManager,
    private val wakeWordNotifier: WakeWordNotifier,
    private val geminiOrchestrator: GeminiOrchestrator,
    private val memoryExtractor: MemoryExtractor,
    private val memoryContextBuilder: MemoryContextBuilder,
    private val memoryRepository: MemoryRepository,
    private val actionExecutor: ActionExecutor,
    private val bookReaderAction: BookReaderAction,
    private val conversationContext: ConversationContext,
    private val contextIntentDetector: ContextIntentDetector,
    private val navigationGuidanceMonitor: NavigationGuidanceMonitor,
) {
    private val _uiState = MutableStateFlow(AssistantUiState(hasApiKey = geminiOrchestrator.hasApiKey()))
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    private var scope: CoroutineScope? = null
    private var isActive = false
    private var isSpeaking = false
    private var listenProfile = ListeningProfile.WAKE_WORD
    private var silentRetries = 0
    private var resumeListeningJob: Job? = null
    private var processingJob: Job? = null
    private var watchdogJob: Job? = null
    private var navigationPauseJob: Job? = null
    private var listeningSuspended = false
    private var lastStateChangeMs = System.currentTimeMillis()

    fun bind(scope: CoroutineScope) {
        this.scope = scope
        scope.launch {
            speechRecognitionManager.audioLevel.collect { level ->
                if (!isSpeaking) {
                    _uiState.update { it.copy(audioLevel = level) }
                }
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
        listenProfile = ListeningProfile.WAKE_WORD
        markState(VoiceState.Idle, "Aquí estoy. Di Lazaro cuando quieras.", awaitingWakeWord = true)
        startWatchdog()
        ensureListening()
    }

    fun stopAssistant() {
        isActive = false
        listeningSuspended = false
        navigationPauseJob?.cancel()
        forceStopOutput()
        navigationGuidanceMonitor.stopNavigation()
        resetCounters()
        stopWatchdog()
        speechRecognitionManager.shutdown()
        markState(VoiceState.Idle, "Asistente detenido.", awaitingWakeWord = true)
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
            "Interrumpido. Responde, repíteme las opciones, o di cancela."
        } else {
            "Interrumpido. Te escucho."
        }
        markState(VoiceState.Listening, hint, awaitingWakeWord = false)
        startDirectListening()
    }

    private fun resumeListening(directAfter: Boolean = false) {
        if (!isActive || isSpeaking) return
        listenProfile = if (directAfter || actionExecutor.hasPendingConfirmation()) {
            ListeningProfile.DIRECT_RESPONSE
        } else {
            ListeningProfile.WAKE_WORD
        }
        resetCounters()
        ensureListening()
    }

    private fun forceStopOutput() {
        isSpeaking = false
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

    private fun ensureListening() {
        if (!isActive || isSpeaking || listeningSuspended) return

        when (resolveListenProfile()) {
            ListeningProfile.WAKE_WORD -> startPassiveWakeListening()
            ListeningProfile.DIRECT_RESPONSE -> startDirectListening()
        }
    }

    private fun startPassiveWakeListening() {
        if (!isActive || isSpeaking || listeningSuspended) return
        if (speechRecognitionManager.isPassiveWakeActive()) return

        markState(
            voiceState = VoiceState.Idle,
            statusMessage = "Escuchando en silencio. Di Lazaro cuando quieras.",
            awaitingWakeWord = true,
        )

        speechRecognitionManager.startPassiveWakeListening(
            onWakeWord = { text ->
                scope?.launch { handleWakeWordDetected(text) }
            },
            onError = { message, isSilent ->
                scope?.launch { handlePassiveListeningError(message, isSilent) }
            },
        )
    }

    private fun startDirectListening() {
        if (!isActive || isSpeaking || listeningSuspended) return

        speechRecognitionManager.stopListening()
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
            awaitingWakeWord = false,
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

    private fun startActiveCommandListening() {
        if (!isActive || isSpeaking || listeningSuspended) return

        speechRecognitionManager.stopListening()
        markState(
            voiceState = VoiceState.Listening,
            statusMessage = "Te escucho, dime.",
            awaitingWakeWord = false,
        )

        speechRecognitionManager.startActiveCommandListening(
            onResult = { text ->
                scope?.launch { handleActiveCommandResult(text) }
            },
            onError = { message, isSilent ->
                scope?.launch { handleSpeechError(message, isSilent) }
            },
        )
    }

    private suspend fun handleWakeWordDetected(rawText: String) {
        if (!isActive || isSpeaking || listeningSuspended) return

        speechRecognitionManager.stopListening()
        resetCounters()
        wakeWordNotifier.playActivationSound()

        val wakeMatch = WakeWordDetector.parse(rawText)
        if (wakeMatch.command.isNotBlank()) {
            markState(
                voiceState = VoiceState.Listening,
                statusMessage = "Te escucho, dime.",
                awaitingWakeWord = false,
                partialTranscript = wakeMatch.command,
            )
            launchProcessUserSpeech(wakeMatch.command)
            return
        }

        startActiveCommandListening()
    }

    private suspend fun handleActiveCommandResult(rawText: String) {
        speechRecognitionManager.stopListening()
        resetCounters()

        val text = rawText.trim()
        if (text.isBlank()) {
            resumeListening(directAfter = false)
            return
        }

        if (contextIntentDetector.isInterruptCommand(rawText)) {
            handleInterruptCommand(rawText)
            return
        }

        launchProcessUserSpeech(text)
    }

    private suspend fun handlePassiveListeningError(message: String, isSilent: Boolean) {
        if (!isActive || isSpeaking || listeningSuspended) return
        if (isSilent) return
        if (message.isNotBlank()) {
            speakOnly(message)
            resumeListening(directAfter = false)
        }
    }

    private fun scheduleListen(delayMs: Long) {
        if (!isActive || isSpeaking || listeningSuspended) return
        cancelScheduledListen()
        resumeListeningJob = scope?.launch {
            if (delayMs > 0) delay(delayMs)
            if (!isActive || isSpeaking || listeningSuspended) return@launch
            ensureListening()
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
        silentRetries = 0
        speechRecognitionManager.stopListening()

        if (contextIntentDetector.isInterruptCommand(rawText)) {
            handleInterruptCommand(rawText)
            return
        }

        launchProcessUserSpeech(rawText.trim())
    }

    private suspend fun handleInterruptCommand(rawText: String) {
        processingJob?.cancel()
        processingJob = null
        speechRecognitionManager.stopListening()
        textToSpeechManager.stop()
        bookReaderAction.stopPlayback()
        isSpeaking = false
        cancelScheduledListen()

        val wakeMatch = WakeWordDetector.parse(rawText)
        val command = if (wakeMatch.detected) wakeMatch.command else rawText

        when {
            contextIntentDetector.isCancelPhrase(command) || actionExecutor.isNegative(command) -> {
                val reply = geminiOrchestrator.handleUserMessage(command)
                speakOnly(reply.spokenText)
                resumeListening(directAfter = actionExecutor.hasPendingConfirmation())
            }
            wakeMatch.detected && command.isNotBlank() -> launchProcessUserSpeech(command)
            else -> {
                navigationGuidanceMonitor.stopNavigation()
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
        speechRecognitionManager.stopListening()
        if (isSilent) {
            scheduleSilentRetry()
            return
        }
        if (message.isNotBlank()) {
            speakOnly(message)
        }
        resumeListening(directAfter = actionExecutor.hasPendingConfirmation())
    }

    private fun scheduleSilentRetry() {
        if (!isActive || isSpeaking || listeningSuspended) return

        if (resolveListenProfile() == ListeningProfile.WAKE_WORD) {
            startPassiveWakeListening()
            return
        }

        silentRetries++
        if (silentRetries > 2) {
            resetCounters()
            resumeListening(directAfter = actionExecutor.hasPendingConfirmation())
            return
        }

        scheduleListen(delayMs = SILENT_RETRY_DELAY_MS)
    }

    private suspend fun processUserSpeech(text: String) {
        try {
            markState(
                voiceState = VoiceState.Processing,
                statusMessage = "Procesando…",
                awaitingWakeWord = false,
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
            } else {
                conversationContext.clearPending()
            }
            _uiState.update { it.copy(lastResponse = reply.spokenText) }

            val needsDirectInput = actionExecutor.hasPendingConfirmation()
            speakOnly(reply.spokenText)

            if (!isActive) return

            if (reply.suspendListening) {
                navigationGuidanceMonitor.startNavigation()
                actionExecutor.runDeferredMapsLaunch()
                enterNavigationPause()
            } else {
                resumeListening(directAfter = needsDirectInput)
            }

            if (!reply.skipAutoLearn && !reply.actionTaken && !reply.suspendListening) {
                scope?.launch { backgroundMaybeLearn(text, reply.spokenText) }
            }
        } catch (e: CancellationException) {
            // Interrupción: handleInterruptCommand o interruptAndListen retoman.
        } catch (e: Exception) {
            speakOnly("Algo falló. Sigo escuchando.")
            resumeListening(directAfter = actionExecutor.hasPendingConfirmation())
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

        _uiState.update { it.copy(lastResponse = question) }
        speakOnly(question)
        resumeListening(directAfter = true)
    }

    private suspend fun speakOnly(message: String) {
        if (!isActive) return

        cancelScheduledListen()
        speechRecognitionManager.stopListening()

        if (message.isBlank()) return

        isSpeaking = true
        markState(voiceState = VoiceState.Speaking, statusMessage = message)
        try {
            textToSpeechManager.speak(message)
        } finally {
            isSpeaking = false
            delay(POST_SPEECH_DELAY_MS)
        }
    }

    private fun enterNavigationPause() {
        cancelScheduledListen()
        speechRecognitionManager.stopListening()
        resetCounters()
        listenProfile = ListeningProfile.WAKE_WORD
        markState(
            voiceState = VoiceState.Idle,
            statusMessage = "Navegando. Lazaro te dira cada giro y vibrara al girar.",
            awaitingWakeWord = true,
        )

        navigationPauseJob?.cancel()
        navigationPauseJob = scope?.launch {
            delay(NAVIGATION_PAUSE_MS)
            if (isActive) {
                resumeAfterNavigationPause()
            }
        }
        startPassiveWakeListening()
    }

    private fun resumeAfterNavigationPause() {
        listeningSuspended = false
        navigationPauseJob?.cancel()
        navigationGuidanceMonitor.stopNavigation()
        markState(
            voiceState = VoiceState.Idle,
            statusMessage = "Aquí estoy otra vez. Di Lazaro cuando quieras.",
            awaitingWakeWord = true,
        )
        startPassiveWakeListening()
    }

    private fun markState(
        voiceState: VoiceState,
        statusMessage: String,
        awaitingWakeWord: Boolean = _uiState.value.awaitingWakeWord,
        partialTranscript: String = _uiState.value.partialTranscript,
    ) {
        lastStateChangeMs = System.currentTimeMillis()
        _uiState.update {
            it.copy(
                voiceState = voiceState,
                statusMessage = statusMessage,
                awaitingWakeWord = awaitingWakeWord,
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

    private fun recoverIfStuck() {
        val state = _uiState.value.voiceState
        val elapsed = System.currentTimeMillis() - lastStateChangeMs

        when {
            state == VoiceState.Processing && processingJob?.isActive != true && elapsed > STUCK_PROCESSING_MS -> {
                processingJob = null
                isSpeaking = false
                markState(VoiceState.Idle, "Recuperado.", awaitingWakeWord = !actionExecutor.hasPendingConfirmation())
                resumeListening(directAfter = actionExecutor.hasPendingConfirmation())
            }
            state == VoiceState.Speaking && !textToSpeechManager.isSpeaking.value && elapsed > STUCK_SPEAKING_MS -> {
                isSpeaking = false
                resumeListening(directAfter = actionExecutor.hasPendingConfirmation())
            }
            state == VoiceState.Idle &&
                resolveListenProfile() == ListeningProfile.WAKE_WORD &&
                !speechRecognitionManager.isPassiveWakeActive() &&
                processingJob?.isActive != true &&
                !isSpeaking &&
                !listeningSuspended &&
                elapsed > STUCK_PASSIVE_MS -> {
                startPassiveWakeListening()
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
        }
    }

    companion object {
        private const val POST_SPEECH_DELAY_MS = 800L
        private const val SILENT_RETRY_DELAY_MS = 1800L
        private const val IDLE_RETRY_DELAY_MS = 2500L
        private const val WATCHDOG_INTERVAL_MS = 8_000L
        private const val STUCK_PROCESSING_MS = 25_000L
        private const val STUCK_SPEAKING_MS = 12_000L
        private const val STUCK_LISTENING_MS = 18_000L
        private const val STUCK_PASSIVE_MS = 60_000L
        private const val NAVIGATION_PAUSE_MS = 45 * 60 * 1000L
    }
}
