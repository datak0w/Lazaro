package io.lazaro.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private enum class ListeningMode {
    PASSIVE_WAKE,
    ACTIVE_COMMAND,
    DIRECT_RESPONSE,
}

@Singleton
class SpeechRecognitionManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())

    private var speechRecognizer: SpeechRecognizer? = null
    private var listeningMode: ListeningMode? = null
    private var isListening = false
    private var wakeWordTriggeredThisSession = false
    private var lastWakeWordTriggerMs = 0L

    private var onWakeWordCallback: ((String) -> Unit)? = null
    private var onResultCallback: ((String) -> Unit)? = null
    private var onErrorCallback: ((message: String, silent: Boolean) -> Unit)? = null

    private var passiveRestartRunnable: Runnable? = null

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun isActive(): Boolean = isListening || listeningMode != null

    fun isPassiveWakeActive(): Boolean = listeningMode == ListeningMode.PASSIVE_WAKE

    fun startPassiveWakeListening(
        locale: Locale = Locale("es", "ES"),
        onWakeWord: (String) -> Unit,
        onError: (message: String, silent: Boolean) -> Unit,
    ) {
        if (!isAvailable()) {
            onError("Reconocimiento de voz no disponible en este dispositivo.", false)
            return
        }

        if (listeningMode == ListeningMode.PASSIVE_WAKE && (isListening || passiveRestartRunnable != null)) {
            return
        }

        stopListening()
        listeningMode = ListeningMode.PASSIVE_WAKE
        onWakeWordCallback = onWakeWord
        onResultCallback = null
        onErrorCallback = onError
        startSession(locale, ListeningMode.PASSIVE_WAKE)
    }

    fun startActiveCommandListening(
        locale: Locale = Locale("es", "ES"),
        onResult: (String) -> Unit,
        onError: (message: String, silent: Boolean) -> Unit,
    ) {
        if (!isAvailable()) {
            onError("Reconocimiento de voz no disponible en este dispositivo.", false)
            return
        }

        stopListening()
        listeningMode = ListeningMode.ACTIVE_COMMAND
        onWakeWordCallback = null
        onResultCallback = onResult
        onErrorCallback = onError
        startSession(locale, ListeningMode.ACTIVE_COMMAND)
    }

    fun startDirectResponseListening(
        locale: Locale = Locale("es", "ES"),
        onResult: (String) -> Unit,
        onError: (message: String, silent: Boolean) -> Unit,
    ) {
        if (!isAvailable()) {
            onError("Reconocimiento de voz no disponible en este dispositivo.", false)
            return
        }

        stopListening()
        listeningMode = ListeningMode.DIRECT_RESPONSE
        onWakeWordCallback = null
        onResultCallback = onResult
        onErrorCallback = onError
        startSession(locale, ListeningMode.DIRECT_RESPONSE)
    }

    /** Compatibilidad con llamadas antiguas. */
    fun startListening(
        locale: Locale = Locale("es", "ES"),
        profile: ListeningProfile = ListeningProfile.WAKE_WORD,
        onResult: (String) -> Unit,
        onError: (message: String, silent: Boolean) -> Unit,
        onPartialResult: ((String) -> Unit)? = null,
    ) {
        when (profile) {
            ListeningProfile.WAKE_WORD -> startPassiveWakeListening(
                locale = locale,
                onWakeWord = { text ->
                    val command = WakeWordDetector.parse(text).command
                    onResult(if (command.isNotBlank()) text else text)
                },
                onError = onError,
            )
            ListeningProfile.DIRECT_RESPONSE -> startDirectResponseListening(
                locale = locale,
                onResult = onResult,
                onError = onError,
            )
        }
        if (onPartialResult != null) {
            // Los resultados parciales ya se publican en _partialText.
        }
    }

    fun stopListening() {
        cancelPassiveRestart()
        listeningMode = null
        isListening = false
        wakeWordTriggeredThisSession = false
        speechRecognizer?.cancel()
        onWakeWordCallback = null
        onResultCallback = null
        onErrorCallback = null
        _partialText.value = ""
        _audioLevel.value = 0f
    }

    fun resetRecognizer() {
        stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    fun shutdown() {
        resetRecognizer()
    }

    private fun startSession(locale: Locale, mode: ListeningMode) {
        if (listeningMode != mode) return

        wakeWordTriggeredThisSession = false
        isListening = true
        ensureRecognizer()
        speechRecognizer?.startListening(buildIntent(locale, mode))
    }

    private fun ensureRecognizer() {
        if (speechRecognizer != null) return
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(recognitionListener)
        }
    }

    private fun buildIntent(locale: Locale, mode: ListeningMode): Intent {
        val (completeSilence, possibleSilence, minLength) = when (mode) {
            ListeningMode.PASSIVE_WAKE -> Triple(18_000L, 14_000L, 350L)
            ListeningMode.ACTIVE_COMMAND -> Triple(7_500L, 5_500L, 180L)
            ListeningMode.DIRECT_RESPONSE -> Triple(7_500L, 5_500L, 180L)
        }

        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "")
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, completeSilence)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, possibleSilence)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, minLength)
        }
    }

    private fun schedulePassiveRestart(delayMs: Long = 120L) {
        if (listeningMode != ListeningMode.PASSIVE_WAKE) return
        cancelPassiveRestart()
        passiveRestartRunnable = Runnable {
            if (listeningMode == ListeningMode.PASSIVE_WAKE && !isListening) {
                startSession(Locale("es", "ES"), ListeningMode.PASSIVE_WAKE)
            }
        }
        mainHandler.postDelayed(passiveRestartRunnable!!, delayMs)
    }

    private fun cancelPassiveRestart() {
        passiveRestartRunnable?.let { mainHandler.removeCallbacks(it) }
        passiveRestartRunnable = null
    }

    private fun maybeTriggerWakeWord(text: String) {
        if (listeningMode != ListeningMode.PASSIVE_WAKE || wakeWordTriggeredThisSession) return
        if (!WakeWordDetector.containsConfidentWakeWord(text)) return

        val now = System.currentTimeMillis()
        if (now - lastWakeWordTriggerMs < 4_000L) return
        lastWakeWordTriggerMs = now
        wakeWordTriggeredThisSession = true

        cancelPassiveRestart()
        listeningMode = null
        isListening = false
        speechRecognizer?.cancel()

        onWakeWordCallback?.invoke(text)
        onWakeWordCallback = null
        onErrorCallback = null
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _partialText.value = ""
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) {
            val normalized = ((rmsdB + 2f) / 10f).coerceIn(0f, 1f)
            _audioLevel.value = normalized
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            val mode = listeningMode ?: return
            isListening = false

            val silent = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                SpeechRecognizer.ERROR_CLIENT,
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER,
                SpeechRecognizer.ERROR_TOO_MANY_REQUESTS,
                SpeechRecognizer.ERROR_AUDIO,
                -> true
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> false
                else -> true
            }

            val message = when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    "Permiso de micrófono denegado."
                else -> ""
            }

            when (mode) {
                ListeningMode.PASSIVE_WAKE -> {
                    if (silent) {
                        schedulePassiveRestart(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 600L else 120L)
                    } else {
                        onErrorCallback?.invoke(message, false)
                    }
                }
                ListeningMode.ACTIVE_COMMAND,
                ListeningMode.DIRECT_RESPONSE,
                -> onErrorCallback?.invoke(message, silent)
            }
        }

        override fun onResults(results: Bundle?) {
            val mode = listeningMode ?: return
            isListening = false

            val candidates = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                .orEmpty()
                .map { it.trim() }
                .filter { it.isNotBlank() }

            when (mode) {
                ListeningMode.PASSIVE_WAKE -> {
                    val best = candidates.firstOrNull { WakeWordDetector.containsConfidentWakeWord(it) }
                    if (best != null) {
                        maybeTriggerWakeWord(best)
                    } else {
                        schedulePassiveRestart()
                    }
                }
                ListeningMode.ACTIVE_COMMAND,
                ListeningMode.DIRECT_RESPONSE,
                -> {
                    val bestMatch = candidates.firstOrNull()
                    if (bestMatch != null) {
                        onResultCallback?.invoke(bestMatch)
                    } else {
                        onErrorCallback?.invoke("", true)
                    }
                }
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            _partialText.value = text
            // En pasivo no activamos por parciales: demasiados falsos positivos.
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}
