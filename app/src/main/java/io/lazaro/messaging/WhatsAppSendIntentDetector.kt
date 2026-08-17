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
        if (t == "cuelga" || t == "colgar" || t == "corta" || t == "cortar") return true
        if (t.contains("cuelga") || t.contains("colgar")) return true
        if (t.contains("corta la llamada") || t.contains("corta llamada")) return true
        if (t.contains("termina la llamada") || t.contains("finaliza la llamada")) return true
        if (t.contains("cuelga la llamada") || t.contains("cuelga llamada")) return true
        return false
    }

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
