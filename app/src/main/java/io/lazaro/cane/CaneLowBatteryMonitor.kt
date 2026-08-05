package io.lazaro.cane

import io.lazaro.cane.ble.CaneBleManager
import io.lazaro.tools.BatteryAction
import io.lazaro.voice.TextToSpeechManager
import io.lazaro.voice.VoiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Aviso hablado cada 5 minutos si la batería del bastón está por debajo del 20%.
 */
@Singleton
class CaneLowBatteryMonitor @Inject constructor(
    private val caneBleManager: CaneBleManager,
    private val batteryAction: BatteryAction,
    private val textToSpeechManager: TextToSpeechManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loopJob: Job? = null
    private val running = AtomicBoolean(false)
    private var lastWarnMs = 0L
    private var voiceStateProvider: (() -> VoiceState)? = null

    fun start(voiceStateProvider: () -> VoiceState) {
        this.voiceStateProvider = voiceStateProvider
        if (running.getAndSet(true)) return
        loopJob = scope.launch {
            while (isActive && running.get()) {
                delay(POLL_MS)
                maybeWarn()
            }
        }
    }

    fun stop() {
        running.set(false)
        loopJob?.cancel()
        loopJob = null
    }

    private suspend fun maybeWarn() {
        val state = caneBleManager.state.value
        if (!state.isConnected) return
        val pct = BatteryAction.sanitizePercent(state.batteryPercent) ?: return
        if (pct >= LOW_THRESHOLD) return

        val now = System.currentTimeMillis()
        if (now - lastWarnMs < INTERVAL_MS) return

        val vs = voiceStateProvider?.invoke()
        if (vs == VoiceState.Listening || vs == VoiceState.Processing || vs == VoiceState.Speaking) {
            return
        }
        if (textToSpeechManager.isSpeaking.value) return

        lastWarnMs = now
        try {
            textToSpeechManager.speak(batteryAction.lowCaneWarningPhrase(pct))
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val LOW_THRESHOLD = 20
        private const val INTERVAL_MS = 5 * 60 * 1000L
        private const val POLL_MS = 30_000L
    }
}
