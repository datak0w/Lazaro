package io.lazaro.messaging

import java.text.Normalizer

/**
 * Detecta «manda un WhatsApp», «envía nota de voz a…», etc.
 */
object WhatsAppSendIntentDetector {

    private val WITH_CONTACT = listOf(
        Regex(
            """^(?:lazaro\s+)?(?:por\s+favor\s+)?(?:me\s+)?(?:puedes\s+)?(?:quiero\s+)?(?:manda|mandar|envia|enviar|manda(?:me)?)\s+(?:un\s+)?(?:mensaje\s+de\s+voz\s+)?(?:por\s+)?(?:whatsapp|wapp|wasap)\s+(?:a|al|para)\s+(.+)$""",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            """^(?:lazaro\s+)?(?:por\s+favor\s+)?(?:me\s+)?(?:puedes\s+)?(?:manda|mandar|envia|enviar)\s+(?:una\s+)?nota\s+de\s+voz\s+(?:a|al|para)\s+(.+)$""",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            """^(?:lazaro\s+)?(?:por\s+favor\s+)?(?:whatsapp|wapp|wasap)\s+(?:a|al|para)\s+(.+)$""",
            RegexOption.IGNORE_CASE,
        ),
    )

    private val GENERIC = listOf(
        "manda un whatsapp",
        "mandar un whatsapp",
        "envia un whatsapp",
        "enviar un whatsapp",
        "manda whatsapp",
        "envia whatsapp",
        "manda un wapp",
        "nota de voz whatsapp",
        "mensaje de voz whatsapp",
    )

    fun detectRecipient(userText: String): DetectedWhatsAppSend? {
        val text = normalize(userText)
        if (text.isBlank()) return null
        if (MessagesIntentDetector.isReadMessagesRequest(userText)) return null

        for (pattern in WITH_CONTACT) {
            val match = pattern.find(text) ?: continue
            var contact = match.groupValues.getOrNull(1)?.trim().orEmpty()
            contact = stripTrailingMessage(contact)
            if (contact.length >= 2) {
                return DetectedWhatsAppSend(recipient = contact, needsRecipient = false)
            }
        }

        if (GENERIC.any { text == it || text.startsWith("$it ") || text.contains(it) }) {
            // «manda un whatsapp a María» ya cubierto; genérico sin destino
            if (!text.contains(" a ") && !text.contains(" al ") && !text.contains(" para ")) {
                return DetectedWhatsAppSend(recipient = null, needsRecipient = true)
            }
        }
        return null
    }

    fun isHangupDuringCall(userText: String): Boolean {
        val t = normalize(userText)
        if (t.isBlank()) return false
        // «descuelga» / «descolgar» son para CONTESAR, no colgar.
        if (t.contains("descuelga") || t.contains("descolgar")) return false
        if (t in HANGUP_EXACT) return true
        val tokens = t.split(' ')
        if (tokens.any { it in HANGUP_EXACT }) return true
        return HANGUP_PHRASES.any { t.contains(it) }
    }

    private val HANGUP_EXACT = setOf(
        "cuelga", "colgar", "corta", "cortar",
        "rechaza", "rechazar", "rechazo",
    )

    private val HANGUP_PHRASES = listOf(
        "cuelga la llamada", "cuelga llamada",
        "corta la llamada", "corta llamada",
        "termina la llamada", "finaliza la llamada",
        "rechaza la llamada", "rechazar la llamada",
    )

    private fun stripTrailingMessage(contact: String): String {
        // «María que llego tarde» → María (el cuerpo se graba por voz)
        val cut = listOf(" que ", " diciendo ", " y dile ", " dile ")
        var out = contact
        for (marker in cut) {
            val idx = out.indexOf(marker)
            if (idx > 2) {
                out = out.substring(0, idx).trim()
                break
            }
        }
        return out.trim()
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

data class DetectedWhatsAppSend(
    val recipient: String?,
    val needsRecipient: Boolean,
)
