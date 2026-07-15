package io.lazaro.sensor

import io.lazaro.voice.TextToSpeechManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VisionAlertManager @Inject constructor(
    private val textToSpeechManager: TextToSpeechManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastSpokenSummary = ""
    private var lastSpokenMs = 0L

    fun bind(piHubBleManager: PiHubBleManager, piHubRepository: PiHubRepository) {
        scope.launch {
            combine(piHubBleManager.state, piHubRepository.config) { state, config ->
                state to config
            }.collect { (state, config) ->
                if (!state.isConnected || !config.visionTtsEnabled) return@collect
                val summary = state.visionSummary.trim()
                if (summary.isBlank() || summary == lastSpokenSummary) return@collect

                val now = System.currentTimeMillis()
                if (now - lastSpokenMs < DEBOUNCE_MS) return@collect

                lastSpokenSummary = summary
                lastSpokenMs = now
                textToSpeechManager.initialize()
                textToSpeechManager.speak(summary)
            }
        }
    }

    companion object {
        private const val DEBOUNCE_MS = 10_000L
    }
}
