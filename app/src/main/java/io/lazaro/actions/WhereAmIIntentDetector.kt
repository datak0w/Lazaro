package io.lazaro.actions

import java.text.Normalizer

/** Detecta «dónde estoy», «mi ubicación», etc. sin pasar por Gemini. */
object WhereAmIIntentDetector {

    private val TRIGGERS = listOf(
        "donde estoy", "donde me encuentro", "donde me hallo",
        "mi ubicacion", "cual es mi ubicacion", "dime mi ubicacion",
        "en que calle estoy", "en que sitio estoy", "donde me encuentro ahora",
        "ubicame", "localizame", "where am i",
    )

    fun detect(userText: String): Boolean {
        val text = normalize(userText)
        if (text.isBlank()) return false
        if (TRIGGERS.any { text.contains(it) }) return true
        // «dónde estoy» suelto o con Lazaro
        return text == "donde" ||
            (text.contains("donde") && text.contains("estoy")) ||
            (text.contains("ubicacion") && (text.contains("mi") || text.contains("dime")))
    }

    private fun normalize(text: String): String {
        return Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
