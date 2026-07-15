package io.lazaro.pathguide

import androidx.camera.core.ImageProxy
import io.lazaro.voice.TextToSpeechManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FrontalObstacleAnnouncer @Inject constructor(
    private val textToSpeechManager: TextToSpeechManager,
    private val obstacleLabeler: ObstacleLabeler,
    private val bypassAdvisor: BypassAdvisor,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastStairMs = 0L
    private var lastFrontalMs = 0L
    private var lastStairMessage = ""
    private var lastFrontalMessage = ""

    fun announceStairs(handrail: HandrailSide, onStart: () -> Unit, onEnd: () -> Unit) {
        val message = when (handrail) {
            HandrailSide.LEFT -> "Escaleras delante. Barandilla a la izquierda."
            HandrailSide.RIGHT -> "Escaleras delante. Barandilla a la derecha."
            HandrailSide.NONE -> "Escaleras delante. Sin barandilla visible. Usa el bastón."
            HandrailSide.UNKNOWN -> "Escaleras delante."
        }
        speakDebounced(
            message = message,
            debounceMs = STAIR_DEBOUNCE_MS,
            lastMs = { lastStairMs },
            setLastMs = { lastStairMs = it },
            lastMessage = { lastStairMessage },
            setLastMessage = { lastStairMessage = it },
            onStart = onStart,
            onEnd = onEnd,
        )
    }

    fun announceFrontalObstacle(
        corridor: CorridorState,
        image: ImageProxy,
        onStart: () -> Unit,
        onEnd: () -> Unit,
    ) {
        scope.launch {
            onStart()
            try {
                val label = obstacleLabeler.labelFromImage(image)
                val advice = bypassAdvisor.advise(corridor)
                val message = buildFrontalMessage(label, advice)
                speakDebounced(
                    message = message,
                    debounceMs = FRONTAL_DEBOUNCE_MS,
                    lastMs = { lastFrontalMs },
                    setLastMs = { lastFrontalMs = it },
                    lastMessage = { lastFrontalMessage },
                    setLastMessage = { lastFrontalMessage = it },
                    onStart = {},
                    onEnd = onEnd,
                )
            } catch (_: Exception) {
                onEnd()
            }
        }
    }

    fun reset() {
        lastStairMs = 0L
        lastFrontalMs = 0L
        lastStairMessage = ""
        lastFrontalMessage = ""
    }

    private fun buildFrontalMessage(label: String, advice: BypassAdvice): String {
        return when (advice.side) {
            BypassSide.STOP -> "Atención. Obstáculo delante. Detente."
            BypassSide.LEFT -> "Atención. Tienes un $label delante. Rodéalo por la izquierda."
            BypassSide.RIGHT -> "Atención. Tienes un $label delante. Rodéalo por la derecha."
            BypassSide.CAUTIOUS_LEFT ->
                "Atención. Tienes un $label delante. Rodéalo por la izquierda, con precaución."
            BypassSide.CAUTIOUS_RIGHT ->
                "Atención. Tienes un $label delante. Rodéalo por la derecha, con precaución."
        }
    }

    private fun speakDebounced(
        message: String,
        debounceMs: Long,
        lastMs: () -> Long,
        setLastMs: (Long) -> Unit,
        lastMessage: () -> String,
        setLastMessage: (String) -> Unit,
        onStart: () -> Unit,
        onEnd: () -> Unit,
    ) {
        val now = System.currentTimeMillis()
        if (message == lastMessage() && now - lastMs() < debounceMs) {
            onEnd()
            return
        }
        setLastMessage(message)
        setLastMs(now)
        onStart()
        scope.launch {
            textToSpeechManager.initialize()
            textToSpeechManager.speak(message)
            onEnd()
        }
    }

    companion object {
        private const val STAIR_DEBOUNCE_MS = 15_000L
        private const val FRONTAL_DEBOUNCE_MS = 12_000L
    }
}
