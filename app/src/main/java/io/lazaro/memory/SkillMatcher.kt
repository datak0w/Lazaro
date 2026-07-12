package io.lazaro.memory

import io.lazaro.memory.entity.CustomSkill
import javax.inject.Inject
import javax.inject.Singleton

data class SkillMatch(
    val skill: CustomSkill,
    val score: Float,
)

@Singleton
class SkillMatcher @Inject constructor(
    private val memoryRepository: MemoryRepository,
) {
    suspend fun findBestMatch(userText: String): SkillMatch? {
        val normalized = normalize(userText)
        if (normalized.isBlank()) return null

        var best: SkillMatch? = null
        for (skill in memoryRepository.getConfirmedSkills()) {
            val phrases = memoryRepository.parseTriggerPhrases(skill.triggerPhrases)
            for (phrase in phrases) {
                val score = matchScore(normalized, normalize(phrase))
                if (score >= 0.75f && (best == null || score > best.score)) {
                    best = SkillMatch(skill, score)
                }
            }
        }
        return best
    }

    private fun matchScore(input: String, trigger: String): Float {
        if (trigger.isBlank()) return 0f
        if (input == trigger) return 1f
        if (input.contains(trigger)) return 0.9f
        if (trigger.contains(input) && input.length >= 4) return 0.8f

        val inputWords = input.split(" ").filter { it.length > 2 }
        val triggerWords = trigger.split(" ").filter { it.length > 2 }
        if (inputWords.isEmpty() || triggerWords.isEmpty()) return 0f

        val overlap = inputWords.count { word -> triggerWords.any { it == word } }
        return overlap.toFloat() / triggerWords.size.toFloat()
    }

    private fun normalize(text: String): String {
        return text.lowercase()
            .replace("lazaro", "")
            .replace("lázaro", "")
            .replace(Regex("[^a-záéíóúüñ0-9\\s]"), "")
            .trim()
    }
}
