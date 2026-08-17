package io.lazaro.vision

import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SceneLookIntentDetector @Inject constructor() {

    fun detect(userText: String): Boolean {
        val t = normalize(userText)
        if (t.isBlank()) return false
        return TRIGGERS.any { t.contains(it) }
    }

    private fun normalize(text: String): String {
        return Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    companion object {
        private val TRIGGERS = listOf(
            "dime que ves", "que ves", "que veo",
            "que hay delante", "que hay enfrente",
            "describe lo que ves", "describe la escena", "describe delante",
            "mira delante", "mira al frente", "mira adelante",
            "que tengo delante", "que hay al frente",
            "foto de delante", "haz una foto y describe", "describe la foto",
            "que hay ahi delante",
            "analiza lo que hay delante", "observa delante",
        )
    }
}
