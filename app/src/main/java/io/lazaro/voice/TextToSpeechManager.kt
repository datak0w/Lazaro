package io.lazaro.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
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

    private val audioManager: AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var focusRequest: AudioFocusRequest? = null

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
                    applySpeechAudioAttributes()
                    tts?.setOnUtteranceProgressListener(utteranceListener)
                } else {
                    Log.e(TAG, "TTS init falló status=$status")
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
        applySpeechAudioAttributes()
    }

    /**
     * Multimedia (mismo volumen que el usuario sube con las teclas).
     * USAGE_ASSISTANCE_ACCESSIBILITY en Samsung a menudo va a un stream a 0
     * → se ve el texto y no se oye nada.
     */
    private fun applySpeechAudioAttributes() {
        val engine = tts ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        try {
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
        } catch (e: Exception) {
            Log.w(TAG, "setAudioAttributes: ${e.message}")
        }
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
        if (!isReady || spoken.isBlank()) {
            Log.w(TAG, "speak omitido ready=$isReady blank=${spoken.isBlank()}")
            return false
        }
        stopRequested.set(false)

        val chunks = SpokenTextCleaner.chunkForTts(spoken)
        if (chunks.isEmpty()) return false

        prepareAudibleOutput()
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
            abandonPlaybackFocus()
        }
    }

    private fun prepareAudibleOutput() {
        val am = audioManager ?: return
        try {
            if (am.mode != AudioManager.MODE_NORMAL) {
                am.mode = AudioManager.MODE_NORMAL
                Log.i(TAG, "mode → NORMAL (antes no normal)")
            }
            // Volumen multimedia a 0 = texto en pantalla y silencio.
            bumpStreamIfMuted(AudioManager.STREAM_MUSIC)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                bumpStreamIfMuted(AudioManager.STREAM_ACCESSIBILITY)
            }
            requestPlaybackFocus(am)
        } catch (e: Exception) {
            Log.w(TAG, "prepareAudibleOutput: ${e.message}")
        }
    }

    private fun bumpStreamIfMuted(stream: Int) {
        val am = audioManager ?: return
        try {
            val max = am.getStreamMaxVolume(stream)
            if (max <= 0) return
            val cur = am.getStreamVolume(stream)
            if (cur == 0) {
                val target = (max * 0.45f).toInt().coerceIn(1, max)
                am.setStreamVolume(stream, target, 0)
                Log.i(TAG, "stream $stream estaba a 0 → $target/$max")
            }
        } catch (e: Exception) {
            Log.w(TAG, "bumpStream $stream: ${e.message}")
        }
    }

    private fun requestPlaybackFocus(am: AudioManager) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener { }
                    .build()
                focusRequest = req
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "requestAudioFocus: ${e.message}")
        }
    }

    private fun abandonPlaybackFocus() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { am.abandonAudioFocusRequest(it) }
                focusRequest = null
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        } catch (_: Exception) {
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
            // Forzar STREAM_MUSIC: se oye con el volumen de medios del Samsung.
            val params = Bundle().apply {
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            }
            val result = engine.speak(chunk, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            if (result == TextToSpeech.ERROR) {
                Log.e(TAG, "speak ERROR chunk=$index len=${chunk.length}")
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
        abandonPlaybackFocus()
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
            Log.w(TAG, "utterance onError id=$utteranceId")
            activeFinish?.invoke()
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            Log.w(TAG, "utterance onError id=$utteranceId code=$errorCode")
            activeFinish?.invoke()
        }
    }

    companion object {
        private const val TAG = "LazaroTTS"
    }
}
