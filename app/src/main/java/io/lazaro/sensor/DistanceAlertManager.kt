package io.lazaro.sensor

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.lazaro.voice.TextToSpeechManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DistanceAlertManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val textToSpeechManager: TextToSpeechManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastAlertCm = Int.MAX_VALUE
    private var lastAlertMs = 0L

    fun bind(piHubBleManager: PiHubBleManager, piHubRepository: PiHubRepository) {
        scope.launch {
            combine(piHubBleManager.state, piHubRepository.config) { state, config ->
                Triple(state, config, System.currentTimeMillis())
            }.collect { (state, config, now) ->
                if (!state.isConnected || !config.distanceAlertsEnabled) return@collect
                if (!state.distOk || state.quality < 30) return@collect

                val threshold = config.distanceAlertCm
                val cm = state.distanceCm
                if (cm <= 0 || cm > threshold) {
                    if (cm > threshold + 15) lastAlertCm = Int.MAX_VALUE
                    return@collect
                }

                val cooldownOk = now - lastAlertMs >= ALERT_COOLDOWN_MS
                val distanceChanged = kotlin.math.abs(cm - lastAlertCm) >= 10
                if (!cooldownOk && !distanceChanged) return@collect

                lastAlertCm = cm
                lastAlertMs = now
                pulseVibration()
                textToSpeechManager.initialize()
                textToSpeechManager.speak("Obstáculo a $cm centímetros")
            }
        }
    }

    private fun pulseVibration() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(VibratorManager::class.java)
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return

        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            VibrationEffect.createWaveform(longArrayOf(0, 120, 80, 120), -1)
        } else {
            @Suppress("DEPRECATION")
            VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        vibrator.vibrate(effect)
    }

    companion object {
        private const val ALERT_COOLDOWN_MS = 3_000L
    }
}
