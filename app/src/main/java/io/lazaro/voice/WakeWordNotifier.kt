package io.lazaro.voice

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WakeWordNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var lastPlayedMs = 0L

    /** Un solo aviso (tono + vibración) al detectar el wake word. Sin notificación del sistema. */
    fun playActivationSound() {
        val now = System.currentTimeMillis()
        if (now - lastPlayedMs < 4_000L) return
        lastPlayedMs = now

        playTone()
        pulseVibration()
    }

    fun clearListeningNotification() {
        // Compatibilidad: ya no publicamos notificación de escucha.
    }

    private fun playTone() {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 75)
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 140)
            tone.release()
        } catch (_: Exception) {
            // Sin sonido: el flujo sigue igual.
        }
    }

    private fun pulseVibration() {
        val vibrator = vibrator() ?: return
        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            VibrationEffect.createOneShot(100, 160)
        } else {
            @Suppress("DEPRECATION")
            VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        vibrator.vibrate(effect)
    }

    private fun vibrator(): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(VibratorManager::class.java)
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
}
