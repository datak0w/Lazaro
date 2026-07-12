package io.lazaro.news

import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NewsIntentDetector @Inject constructor() {

    fun detect(userText: String): Boolean {
        val text = normalize(userText)
        if (text.isBlank()) return false
        return READ_TRIGGERS.any { text.contains(it) }
    }

    private fun normalize(text: String): String {
        val withoutAccents = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        return withoutAccents
            .lowercase()
            .replace("lazaro", " ")
            .replace("lázaro", " ")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    companion object {
        private val READ_TRIGGERS = listOf(
            "noticias de hoy",
            "noticias de espana",
            "noticias en espana",
            "titulares de hoy",
            "titulares de espana",
            "lee las noticias",
            "leeme las noticias",
            "leeme noticias",
            "lee noticias",
            "cuentame las noticias",
            "cuentame noticias",
            "que pasa en espana",
            "que pasa hoy",
            "pon las noticias",
            "pon noticias",
            "las noticias",
            "noticias",
            "telediario",
            "informativos",
            "actualidad",
            "resumen de noticias",
            "titulares",
        )
    }
}
