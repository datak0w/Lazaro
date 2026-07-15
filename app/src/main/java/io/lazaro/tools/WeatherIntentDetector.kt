package io.lazaro.tools

import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WeatherIntentDetector @Inject constructor() {

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
            .replace(Regex("[^a-z0-9\\s/]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    companion object {
        private val TRIGGERS = listOf(
            "que tiempo hace",
            "qué tiempo hace",
            "que tiempo va a hacer",
            "qué tiempo va a hacer",
            "como esta el tiempo",
            "cómo está el tiempo",
            "como estara el tiempo",
            "tiempo en ojen",
            "tiempo para ojen",
            "clima en ojen",
            "clima para ojen",
            "va a llover",
            "llovera",
            "lloverá",
            "temperatura en ojen",
            "prevision del tiempo",
            "previsión del tiempo",
            "tiempo de manana",
            "tiempo de mañana",
            "tiempo hoy",
            "el tiempo",
        )
    }
}
