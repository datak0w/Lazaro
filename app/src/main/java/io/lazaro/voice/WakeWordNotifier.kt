package io.lazaro.voice

import android.media.AudioManager
import android.media.ToneGenerator
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WakeWordNotifier @Inject constructor() {
    private var lastPlayedMs = 0L

    fun playActivationSound() {
        val now = System.currentTimeMillis()
        if (now - lastPlayedMs < 4_000L) return
        lastPlayedMs = now

        try {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60)
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 100)
            tone.release()
        } catch (_: Exception) {
            // Sin sonido: el flujo sigue igual.
        }
    }
}
