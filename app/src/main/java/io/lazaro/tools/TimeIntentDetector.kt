package io.lazaro.tools

import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TimeIntentDetector @Inject constructor() {

    fun detect(userText: String): Boolean {
        val text = normalize(userText)
        if (text.isBlank()) return false
        return TRIGGERS.any { text.contains(it) }
    }

    private fun normalize(text: String): String {
        return Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace("lazaro", " ")
            .replace("lázaro", " ")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    companion object {
        private val TRIGGERS = listOf(
            "que hora es",
            "qué hora es",
            "dime la hora",
            "me dices la hora",
            "hora actual",
            "que hora tenemos",
            "qué hora tenemos",
            "a que hora estamos",
            "a qué hora estamos",
        )
    }
}
