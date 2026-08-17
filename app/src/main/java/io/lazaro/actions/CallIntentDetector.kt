package io.lazaro.actions

import java.text.Normalizer

/**
 * Detecta «llama a María», «llamar a mamá», etc. sin pasar por Gemini.
 */
object CallIntentDetector {

    private val PATTERNS = listOf(
        Regex(
            """^(?:lazaro\s+)?(?:por\s+favor\s+)?(?:me\s+)?(?:puedes\s+)?(?:quiero\s+)?(?:haz(?:me)?\s+)?(?:una\s+)?llamad[ae]\s+(?:a|al|con)\s+(.+)$""",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            """^(?:lazaro\s+)?(?:por\s+favor\s+)?(?:me\s+)?(?:puedes\s+)?llama(?:r)?(?:me)?\s+(?:a|al|con)\s+(.+)$""",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            """^(?:lazaro\s+)?(?:por\s+favor\s+)?(?:me\s+)?(?:puedes\s+)?marca(?:r)?\s+(?:a|al)\s+(.+)$""",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            """^(?:lazaro\s+)?(?:por\s+favor\s+)?(?:me\s+)?(?:puedes\s+)?pon(?:me)?\s+(?:en\s+)?llamada\s+(?:con|a|al)\s+(.+)$""",
            RegexOption.IGNORE_CASE,
        ),
    )

    fun detectContactQuery(userText: String): String? {
        val text = normalize(userText)
        if (text.isBlank()) return null
        // Evitar «llama a Maps» / comandos que no son teléfono
        if (text.contains("whatsapp") || text.contains("mapa") || text.contains("maps")) {
            return null
        }
        for (pattern in PATTERNS) {
            val match = pattern.find(text) ?: continue
            val contact = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (contact.length >= 2) return contact
        }
        return null
    }

    private fun normalize(text: String): String {
        return Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9\\s+]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
