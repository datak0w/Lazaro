package io.lazaro.voice

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chirp corto «ya escucho». Usa [CueAudioPlayer] (ACCESSIBILITY/MEDIA) para oírse
 * en Samsung y Pixel con el mismo volumen que TTS.
 */
@Singleton
class ListeningCueTone @Inject constructor(
    private val cueAudioPlayer: CueAudioPlayer,
) {
    @Volatile
    private var lastPlayMs = 0L

    fun play() {
        val now = System.currentTimeMillis()
        if (now - lastPlayMs < DEBOUNCE_MS) return
        lastPlayMs = now
        Thread {
            val pcm = cueAudioPlayer.generateToneSweep(
                durationMs = DURATION_MS,
                freqStartHz = 660f,
                freqEndHz = 990f,
                amplitude = AMPLITUDE,
            )
            cueAudioPlayer.playMonoPcm(pcm)
        }.start()
    }

    companion object {
        private const val AMPLITUDE = 0.42f
        private const val DURATION_MS = 180
        private const val DEBOUNCE_MS = 350L
    }
}
