package io.lazaro.pathguide

import io.lazaro.voice.TextToSpeechManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SceneDescriptionAnnouncer @Inject constructor(
    private val textToSpeechManager: TextToSpeechManager,
) {
    private var lastMessage = ""
    private var lastSpokenMs = 0L

    suspend fun announce(message: String, minIntervalMs: Long): Boolean {
        if (message.isBlank()) return false
        val now = System.currentTimeMillis()
        if (message == lastMessage && now - lastSpokenMs < minIntervalMs) return false
        if (now - lastSpokenMs < minIntervalMs) return false

        lastMessage = message
        lastSpokenMs = now
        textToSpeechManager.initialize()
        return textToSpeechManager.speak(message)
    }

    fun reset() {
        lastMessage = ""
        lastSpokenMs = 0L
    }
}
