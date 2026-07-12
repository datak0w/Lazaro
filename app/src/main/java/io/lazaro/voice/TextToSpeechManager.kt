package io.lazaro.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@Singleton
class TextToSpeechManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private var tts: TextToSpeech? = null
    private var isReady = false
    private var currentLocale = Locale("es", "ES")
    private val stopRequested = AtomicBoolean(false)

    suspend fun initialize(locale: Locale = Locale("es", "ES")) {
        if (tts != null && isReady) {
            setLanguage(locale)
            return
        }
        currentLocale = locale
        suspendCoroutine { continuation ->
            tts = TextToSpeech(context) { status ->
                isReady = status == TextToSpeech.SUCCESS
                if (isReady) {
                    setLanguage(currentLocale)
                    tts?.setOnUtteranceProgressListener(utteranceListener)
                }
                continuation.resume(Unit)
            }
        }
    }

    fun setLanguage(locale: Locale) {
        currentLocale = locale
        tts?.language = locale
    }

    suspend fun speak(text: String): Boolean {
        if (!isReady || text.isBlank()) return false
        stopRequested.set(false)
        return suspendCoroutine { continuation ->
            val utteranceId = "lazaro-${System.currentTimeMillis()}"
            val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (result == TextToSpeech.ERROR) {
                _isSpeaking.value = false
                continuation.resume(false)
                return@suspendCoroutine
            }
            utteranceListener.onComplete = {
                continuation.resume(!stopRequested.get())
            }
        }
    }

    fun stop() {
        stopRequested.set(true)
        tts?.stop()
        _isSpeaking.value = false
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }

    private val utteranceListener = object : UtteranceProgressListener() {
        var onComplete: (() -> Unit)? = null

        override fun onStart(utteranceId: String?) {
            _isSpeaking.value = true
        }

        override fun onDone(utteranceId: String?) {
            _isSpeaking.value = false
            onComplete?.invoke()
            onComplete = null
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            _isSpeaking.value = false
            onComplete?.invoke()
            onComplete = null
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            _isSpeaking.value = false
            onComplete?.invoke()
            onComplete = null
        }
    }
}
