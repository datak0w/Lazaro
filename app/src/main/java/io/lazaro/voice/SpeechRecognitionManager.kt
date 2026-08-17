package io.lazaro.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicInteger
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
    private val sessionId = AtomicInteger(0)
    private var listenerSessionId = 0

    private var onResultCallback: ((String) -> Unit)? = null
    private var onErrorCallback: ((message: String, silent: Boolean) -> Unit)? = null

    /** Último parcial: Samsung a veces no entrega onResults y sí parciales. */
    @Volatile
    private var lastPartial: String = ""

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
        runOnMain {
            beginListening(ListeningMode.ACTIVE_COMMAND, onResult, onError)
        }
    }

    fun startDirectResponseListening(
        onResult: (String) -> Unit,
        onError: (message: String, silent: Boolean) -> Unit,
    ) {
        if (!isAvailable()) {
            onError("Reconocimiento de voz no disponible en este dispositivo.", false)
            return
        }
        runOnMain {
            beginListening(ListeningMode.DIRECT_RESPONSE, onResult, onError)
        }
    }

    fun stopListening() {
        runOnMain { stopListeningInternal(destroy = false) }
    }

    fun releaseRecognizer() {
        runOnMain { stopListeningInternal(destroy = true) }
    }

    fun shutdown() {
        releaseRecognizer()
    }

    private fun beginListening(
        mode: ListeningMode,
        onResult: (String) -> Unit,
        onError: (message: String, silent: Boolean) -> Unit,
    ) {
        stopListeningInternal(destroy = true)
        listeningMode = mode
        onResultCallback = onResult
        onErrorCallback = onError
        lastPartial = ""
        startSession(mode)
    }

    private fun stopListeningInternal(destroy: Boolean) {
        sessionId.incrementAndGet()
        listeningMode = null
        isListening = false
        onResultCallback = null
        onErrorCallback = null
        lastPartial = ""
        try {
            speechRecognizer?.cancel()
        } catch (_: Exception) {
        }
        if (destroy) {
            try {
                speechRecognizer?.destroy()
            } catch (_: Exception) {
            }
            speechRecognizer = null
        }
        _partialText.value = ""
        _audioLevel.value = 0f
    }

    private fun startSession(mode: ListeningMode) {
        if (listeningMode != mode) return
        isListening = true
        val id = sessionId.incrementAndGet()
        listenerSessionId = id
        ensureRecognizer(id)
        try {
            speechRecognizer?.startListening(buildIntent(mode))
        } catch (e: Exception) {
            Log.e(TAG, "startListening falló: ${e.message}", e)
            isListening = false
            listeningMode = null
            val err = onErrorCallback
            onResultCallback = null
            onErrorCallback = null
            err?.invoke("No pude abrir el micrófono.", false)
        }
    }

    private fun ensureRecognizer(id: Int) {
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(newListener(id))
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun isCurrentSession(id: Int): Boolean =
        id == listenerSessionId && id == sessionId.get()

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

    private fun newListener(id: Int) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (!isCurrentSession(id)) return
            _partialText.value = ""
            lastPartial = ""
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) {
            if (!isCurrentSession(id)) return
            val normalized = ((rmsdB + 2f) / 10f).coerceIn(0f, 1f)
            _audioLevel.value = normalized
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            if (!isCurrentSession(id)) return
            if (listeningMode == null && onErrorCallback == null && onResultCallback == null) {
                return
            }
            isListening = false
            val silent = error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
            val message = if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                "Permiso de micrófono denegado."
            } else {
                ""
            }

            val partial = lastPartial.trim()
            val resultCb = onResultCallback
            val errorCb = onErrorCallback
            onResultCallback = null
            onErrorCallback = null
            listeningMode = null
            lastPartial = ""
            _audioLevel.value = 0f

            // Samsung: a menudo ERROR_NO_MATCH / CLIENT tras hablar, con parcial bueno.
            if (partial.length >= 2 &&
                resultCb != null &&
                error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
            ) {
                Log.i(TAG, "STT onError($error) usando parcial: ${partial.take(80)}")
                mainHandler.post { resultCb.invoke(partial) }
                return
            }

            Log.i(TAG, "STT onError code=$error silent=$silent")
            if (errorCb != null) {
                mainHandler.post { errorCb.invoke(message, silent) }
            }
        }

        override fun onResults(results: Bundle?) {
            if (!isCurrentSession(id)) return
            val resultCb = onResultCallback
            val errorCb = onErrorCallback
            isListening = false
            listeningMode = null
            onResultCallback = null
            onErrorCallback = null

            val candidates = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                .orEmpty()
                .map { it.trim() }
                .filter { it.isNotBlank() }

            val bestMatch = candidates.firstOrNull()?.takeIf { it.isNotBlank() }
                ?: lastPartial.trim().takeIf { it.length >= 2 }
            lastPartial = ""
            _partialText.value = ""
            _audioLevel.value = 0f

            if (bestMatch != null && resultCb != null) {
                Log.i(TAG, "STT onResults: ${bestMatch.take(80)}")
                mainHandler.post { resultCb.invoke(bestMatch) }
            } else if (errorCb != null) {
                Log.i(TAG, "STT onResults vacío")
                mainHandler.post { errorCb.invoke("", true) }
            } else {
                Log.w(TAG, "STT onResults sin callback (resultado descartado)")
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!isCurrentSession(id)) return
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
                .trim()
            if (text.isNotBlank()) {
                lastPartial = text
                _partialText.value = text
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    companion object {
        private const val TAG = "LazaroSTT"
    }
}
