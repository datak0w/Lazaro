package io.lazaro.voice

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chirp corto «ya escucho». Usa [CueAudioPlayer] (ACCESSIBILITY/MEDIA) y, si falla,
 * [ToneGenerator] por STREAM_MUSIC para no perder el feedback en Samsung/Pixel.
 */
@Singleton
class ListeningCueTone @Inject constructor(
    private val cueAudioPlayer: CueAudioPlayer,
) {
    private val playing = AtomicBoolean(false)
    @Volatile
    private var lastPlayMs = 0L

    private val executor = Executors.newSingleThreadExecutor(
        ThreadFactory { r ->
            Thread(r, "listening-cue").apply {
                priority = Thread.NORM_PRIORITY + 1
                isDaemon = true
            }
        },
    )

    fun play() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPlayMs < DEBOUNCE_MS) return
        lastPlayMs = now
        if (!playing.compareAndSet(false, true)) return
        executor.execute {
            try {
                // Corta tono de espera u otro cue a medias antes del chirp «te escucho».
                cueAudioPlayer.stopCurrent()
                playPcmCue() || playToneGeneratorFallback()
            } catch (e: Exception) {
                Log.w(TAG, "cue falló: ${e.message}")
                try {
                    playToneGeneratorFallback()
                } catch (_: Exception) {
                }
            } finally {
                playing.set(false)
            }
        }
    }

    private fun playPcmCue(): Boolean {
        val pcm = cueAudioPlayer.generateTwoTonePulse(
            durationMs = DURATION_MS,
            freq1Hz = 880f,
            freq2Hz = 1240f,
            amplitude = AMPLITUDE,
        )
        return cueAudioPlayer.playMonoPcm(pcm, amplitudeScale = 1f)
    }

    private fun playToneGeneratorFallback(): Boolean {
        var tg: ToneGenerator? = null
        return try {
            tg = ToneGenerator(AudioManager.STREAM_MUSIC, TONE_VOLUME)
            tg.startTone(ToneGenerator.TONE_PROP_ACK, TONE_MS)
            Thread.sleep((TONE_MS + 40).toLong())
            true
        } catch (e: Exception) {
            Log.w(TAG, "ToneGenerator falló: ${e.message}")
            false
        } finally {
            try {
                tg?.release()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private const val TAG = "ListeningCueTone"
        private const val AMPLITUDE = 0.78f
        private const val DURATION_MS = 220
        private const val DEBOUNCE_MS = 280L
        private const val TONE_VOLUME = 92
        private const val TONE_MS = 200
    }
}
