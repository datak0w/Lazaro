package io.lazaro.messaging

import java.text.Normalizer

/**
 * Detecta «lee mensajes», «lee WhatsApp», «qué mensajes tengo», etc.
 */
object MessagesIntentDetector {

    private val TRIGGERS = listOf(
        "lee mensajes", "leer mensajes", "leeme mensajes", "leeme los mensajes",
        "lee los mensajes", "lee mis mensajes", "leer mis mensajes",
        "lee whatsapp", "leer whatsapp", "leeme whatsapp", "lee el whatsapp",
        "mensajes nuevos", "mensajes sin leer", "tienes mensajes",
        "que mensajes", "que whatsapp", "hay mensajes", "hay whatsapp",
        "revisa mensajes", "revisa whatsapp", "mira mensajes", "mira whatsapp",
        "dime los mensajes", "dime mensajes", "mensajes pendientes",
    )

    fun isReadMessagesRequest(userText: String): Boolean {
        val text = normalize(userText)
        if (text.isBlank()) return false
        if (TRIGGERS.any { text.contains(it) }) return true
        val hasLee = text.contains("lee") || text.contains("leer") || text.contains("leeme")
        val hasMsg = text.contains("mensaje") || text.contains("whatsapp") || text.contains("wapp")
        return hasLee && hasMsg
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
