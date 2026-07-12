package io.lazaro.audiobook

import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookIntentDetector @Inject constructor() {

    fun detectQuery(userText: String): BookIntent? {
        val text = normalize(userText)
        if (text.isBlank()) return null

        if (matchesAny(text, CONTINUE_TRIGGERS)) {
            return BookIntent.Continue
        }

        if (!matchesAny(text, READ_TRIGGERS)) return null

        val title = extractTitle(text)
        return BookIntent.Read(title)
    }

    private fun extractTitle(text: String): String? {
        val patterns = listOf(
            Regex("""(?:leeme|lee)\s+(?:el\s+)?libro\s+(?:de\s+|del\s+|sobre\s+)?(.+)"""),
            Regex("""(?:leeme|lee)\s+(?:un\s+)?(?:libro\s+)?(.+)"""),
            Regex("""audiolibro\s+(?:de\s+)?(.+)"""),
        )
        for (pattern in patterns) {
            val match = pattern.find(text)
            val candidate = match?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (candidate.isNotBlank() && candidate !in GENERIC_WORDS) {
                return candidate
            }
        }
        return null
    }

    private fun matchesAny(text: String, triggers: List<String>): Boolean {
        return triggers.any { text.contains(it) }
    }

    private fun normalize(text: String): String {
        return Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace("lazaro", " ")
            .replace("lazaro", " ")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    companion object {
        private val READ_TRIGGERS = listOf(
            "leeme un libro", "leeme el libro", "lee un libro", "lee el libro",
            "leeme libro", "audiolibro", "pon un audiolibro", "leeme don",
            "leeme la odisea", "leeme quijote",
        )
        private val CONTINUE_TRIGGERS = listOf(
            "continua el libro", "continua leyendo", "sigue leyendo", "sigue el libro",
        )
        private val GENERIC_WORDS = setOf("un", "uno", "el", "libro", "audiolibro", "gratis")
    }
}

sealed class BookIntent {
    data class Read(val titleQuery: String?) : BookIntent()
    data object Continue : BookIntent()
}
