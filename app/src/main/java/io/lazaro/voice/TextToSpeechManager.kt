package io.lazaro.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.lazaro.assistant.SleepModeController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class TextToSpeechManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sleepModeController: SleepModeController,
) {
    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private var tts: TextToSpeech? = null
    private var isReady = false
    private var currentLocale = Locale("es", "ES")
    private val stopRequested = AtomicBoolean(false)

    @Volatile
    private var activeFinish: (() -> Unit)? = null

    suspend fun initialize(locale: Locale = Locale("es", "ES")) {
        if (tts != null && isReady) {
            setLanguage(locale)
            return
        }
        currentLocale = locale
        suspendCancellableCoroutine { continuation ->
            tts = TextToSpeech(context) { status ->
                isReady = status == TextToSpeech.SUCCESS
                if (isReady) {
                    setLanguage(currentLocale)
                    tts?.setOnUtteranceProgressListener(utteranceListener)
                }
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }

    fun setLanguage(locale: Locale) {
        currentLocale = locale
        val engine = tts ?: return
        engine.language = locale
        preferMaleSpanishVoice(engine, locale)
    }

    /**
     * Elige voz masculina en español si el motor la ofrece (Google/Samsung).
     * Lazaro → voz de hombre por defecto.
     */
    private fun preferMaleSpanishVoice(engine: TextToSpeech, locale: Locale) {
        val voices = try {
            engine.voices
        } catch (_: Exception) {
            null
        } ?: return

        val spanish = voices.filter { voice ->
            voice.locale.language.equals("es", ignoreCase = true) &&
                !voice.isNetworkConnectionRequired
        }.ifEmpty {
            voices.filter { it.locale.language.equals("es", ignoreCase = true) }
        }

        val preferred = spanish
            .sortedWith(
                compareByDescending<Voice> { maleScore(it) }
                    .thenByDescending { it.quality }
                    .thenByDescending { localeMatchScore(it.locale, locale) }
                    .thenBy { it.name },
            )
            .firstOrNull { maleScore(it) > 0 }
            ?: spanish
                .filter { maleScore(it) >= 0 }
                .maxWithOrNull(
                    compareByDescending<Voice> { it.quality }
                        .thenByDescending { localeMatchScore(it.locale, locale) },
                )

        if (preferred != null) {
            val ok = engine.setVoice(preferred)
            Log.i(TAG, "TTS voz=${preferred.name} locale=${preferred.locale} set=$ok")
            engine.setPitch(1.0f)
            engine.setSpeechRate(1.0f)
        } else {
            Log.i(TAG, "TTS sin voz masculina explícita; pitch ligeramente grave")
            engine.setPitch(0.88f)
            engine.setSpeechRate(1.0f)
        }
    }

    /** >0 masculino, <0 femenino, 0 desconocido. */
    private fun maleScore(voice: Voice): Int {
        val name = voice.name.lowercase(Locale.ROOT)
        val features = voice.features.joinToString(" ").lowercase(Locale.ROOT)
        val blob = "$name $features"
        if (blob.contains("female") || blob.contains("mujer") ||
            blob.contains("woman") || blob.contains("#female")
        ) {
            return -2
        }
        if (blob.contains("male") || blob.contains("hombre") ||
            blob.contains("man") || blob.contains("#male")
        ) {
            return 3
        }
        if (name.contains("x-eed") || name.contains("es-es-x-eed")) return 2
        if (name.contains("x-eef") || name.contains("x-eeg") || name.contains("x-eeh")) return -1
        if (Regex("""(^|[-_])m([-_]|$)""").containsMatchIn(name)) return 1
        if (Regex("""(^|[-_])f([-_]|$)""").containsMatchIn(name)) return -1
        return 0
    }

    private fun localeMatchScore(voiceLocale: Locale, wanted: Locale): Int {
        var score = 0
        if (voiceLocale.language.equals(wanted.language, ignoreCase = true)) score += 2
        if (voiceLocale.country.equals(wanted.country, ignoreCase = true)) score += 2
        return score
    }

    /**
     * Habla el texto completo en trozos secuenciales (QUEUE_FLUSH).
     * Así [stop] corta de verdad en Samsung/Google, sin cola residual.
     * @param allowDuringSleep solo para anuncios de entrar/salir del modo dormir.
     * @return true si terminó entero; false si se canceló, falló o está en sleep.
     */
    suspend fun speak(text: String, allowDuringSleep: Boolean = false): Boolean {
        if (sleepModeController.isSleeping() && !allowDuringSleep) return false
        val spoken = SpokenTextCleaner.forSpeech(text)
        if (!isReady || spoken.isBlank()) return false
        stopRequested.set(false)

        val chunks = SpokenTextCleaner.chunkForTts(spoken)
        if (chunks.isEmpty()) return false

        _isSpeaking.value = true
        return try {
            for ((index, chunk) in chunks.withIndex()) {
                if (stopRequested.get()) return false
                val ok = speakChunk(chunk, index)
                if (!ok || stopRequested.get()) return false
            }
            true
        } finally {
            _isSpeaking.value = false
            activeFinish = null
        }
    }

    private suspend fun speakChunk(chunk: String, index: Int): Boolean {
        val engine = tts ?: return false
        return suspendCancellableCoroutine { continuation ->
            val finished = AtomicBoolean(false)
            fun finish(ok: Boolean) {
                if (!finished.compareAndSet(false, true)) return
                activeFinish = null
                if (continuation.isActive) continuation.resume(ok)
            }

            activeFinish = { finish(!stopRequested.get()) }
            continuation.invokeOnCancellation {
                stopRequested.set(true)
                try {
                    engine.stop()
                } catch (_: Exception) {
                }
                finish(false)
            }

            val utteranceId = "lazaro-${System.currentTimeMillis()}-$index"
            val result = engine.speak(chunk, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (result == TextToSpeech.ERROR) {
                finish(false)
            }
        }
    }

    fun stop() {
        stopRequested.set(true)
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        _isSpeaking.value = false
        activeFinish?.invoke()
        activeFinish = null
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }

    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            _isSpeaking.value = true
        }

        override fun onDone(utteranceId: String?) {
            activeFinish?.invoke()
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            activeFinish?.invoke()
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            activeFinish?.invoke()
        }
    }

    companion object {
        private const val TAG = "LazaroTTS"
    }
}
