package io.lazaro.pathguide

import io.lazaro.voice.TextToSpeechManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DoorwayGuideAnnouncer @Inject constructor(
    private val textToSpeechManager: TextToSpeechManager,
) {
    private val lastSpokenMs = mutableMapOf<String, Long>()

    suspend fun announce(cue: DoorwayVoiceCue): Boolean {
        val now = System.currentTimeMillis()
        val lastMs = lastSpokenMs[cue.cueId] ?: 0L
        if (now - lastMs < cue.debounceMs) return false

        lastSpokenMs[cue.cueId] = now
        textToSpeechManager.initialize()
        return textToSpeechManager.speak(cue.message)
    }

    fun reset() {
        lastSpokenMs.clear()
    }
}
