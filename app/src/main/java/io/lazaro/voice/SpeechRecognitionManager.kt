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
    private val locale = Locale("es", "ES")

    private var speechRecognizer: SpeechRecognizer? = null
    private var listeningMode: ListeningMode? = null
    private var isListening = false

    private var onResultCallback: ((String) -> Unit)? = null
    private var onErrorCallback: ((message: String, silent: Boolean) -> Unit)? = null

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun isActive(): Boolean = isListening

    fun startActiveCommandListening(
        onResult: (String) -> Unit,
        onError: (message: String, silent: Boolean) -> Unit,
    ) {
        if (!isAvailable()) {
            onError("Reconocimiento de voz no disponible en este dispositivo.", false)
            return
        }

        stopListening()
        listeningMode = ListeningMode.ACTIVE_COMMAND
        onResultCallback = onResult
        onErrorCallback = onError
        startSession(ListeningMode.ACTIVE_COMMAND)
    }

    fun startDirectResponseListening(
        onResult: (String) -> Unit,
        onError: (message: String, silent: Boolean) -> Unit,
    ) {
        if (!isAvailable()) {
            onError("Reconocimiento de voz no disponible en este dispositivo.", false)
            return
        }

        stopListening()
        listeningMode = ListeningMode.DIRECT_RESPONSE
        onResultCallback = onResult
        onErrorCallback = onError
        startSession(ListeningMode.DIRECT_RESPONSE)
    }

    fun stopListening() {
        listeningMode = null
        isListening = false
        speechRecognizer?.cancel()
        onResultCallback = null
        onErrorCallback = null
        _partialText.value = ""
        _audioLevel.value = 0f
    }

    fun releaseRecognizer() {
        stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    fun shutdown() {
        releaseRecognizer()
    }

    private fun startSession(mode: ListeningMode) {
        if (listeningMode != mode) return
        isListening = true
        ensureRecognizer()
        speechRecognizer?.startListening(buildIntent(mode))
    }

    private fun ensureRecognizer() {
        if (speechRecognizer != null) return
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(recognitionListener)
        }
    }

    private fun buildIntent(mode: ListeningMode): Intent {
        val silenceMs = when (mode) {
            ListeningMode.ACTIVE_COMMAND -> SamsungVoiceCompat.commandSilenceTimeoutMs
            ListeningMode.DIRECT_RESPONSE -> SamsungVoiceCompat.directSilenceTimeoutMs
        }
        val possibleSilence = (silenceMs * 0.7).toLong()

        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "")
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, possibleSilence)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 400L)
        }
    }

    private fun endActiveSession() {
        isListening = false
        speechRecognizer?.cancel()
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
            val silent = error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
            val message = if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                "Permiso de micrófono denegado."
            } else {
                ""
            }

            when (mode) {
                ListeningMode.ACTIVE_COMMAND,
                ListeningMode.DIRECT_RESPONSE,
                -> {
                    endActiveSession()
                    onErrorCallback?.invoke(message, silent)
                }
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

            val bestMatch = candidates.firstOrNull()

            when (mode) {
                ListeningMode.ACTIVE_COMMAND,
                ListeningMode.DIRECT_RESPONSE,
                -> {
                    endActiveSession()
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
            if (text.isNotBlank()) {
                _partialText.value = text
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}
