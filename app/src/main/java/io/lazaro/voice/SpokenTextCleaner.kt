package io.lazaro.voice

/**
 * Limpia texto para TTS: quita markdown. No acorta el contenido
 * (las respuestas largas deben oírse enteras).
 */
object SpokenTextCleaner {

    fun forSpeech(raw: String): String {
        var t = raw.trim()
        if (t.isEmpty()) return t

        // Enlaces [texto](url) → texto
        t = t.replace(Regex("""\[([^\]]+)]\([^)]+\)"""), "$1")
        // Imágenes ![alt](url) → alt
        t = t.replace(Regex("""!\[([^\]]*)]\([^)]+\)"""), "$1")
        // Negrita / cursiva / código
        t = t.replace(Regex("""\*\*\*(.+?)\*\*\*"""), "$1")
        t = t.replace(Regex("""\*\*(.+?)\*\*"""), "$1")
        t = t.replace(Regex("""__(.+?)__"""), "$1")
        t = t.replace(Regex("""\*(.+?)\*"""), "$1")
        t = t.replace(Regex("""_(.+?)_"""), "$1")
        t = t.replace(Regex("""`{1,3}([^`]+)`{1,3}"""), "$1")
        // Encabezados / listas / citas
        t = t.replace(Regex("""(?m)^#{1,6}\s*"""), "")
        t = t.replace(Regex("""(?m)^>\s*"""), "")
        t = t.replace(Regex("""(?m)^[-*+]\s+"""), "")
        t = t.replace(Regex("""(?m)^\d+\.\s+"""), "")
        // Asteriscos / almohadillas sueltos que queden
        t = t.replace("***", " ")
        t = t.replace("**", " ")
        t = t.replace("__", " ")
        t = t.replace("*", " ")
        t = t.replace("#", " ")
        t = t.replace("`", " ")
        // URLs crudas (evitar deletrear http…)
        t = t.replace(Regex("""https?://\S+"""), " ")
        // Espacios
        t = t.replace(Regex("""\s+"""), " ").trim()
        return t
    }

    /**
     * Acorta solo cuando se pide explícitamente (tips cortos, etc.).
     * No usar en respuestas normales del asistente.
     */
    fun truncateForSpeech(
        text: String,
        maxChars: Int = BRIEF_MAX_CHARS,
        maxSentences: Int = BRIEF_MAX_SENTENCES,
    ): String {
        val t = text.trim()
        if (t.isEmpty()) return t
        val sentences = t.split(Regex("""(?<=[.!?…])\s+"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val kept = if (sentences.size <= maxSentences) {
            sentences
        } else {
            sentences.take(maxSentences)
        }
        var out = kept.joinToString(" ").trim()
        if (out.length <= maxChars) return out
        val cut = out.take(maxChars)
        val lastSpace = cut.lastIndexOf(' ')
        out = if (lastSpace >= maxChars / 2) cut.take(lastSpace) else cut
        return out.trimEnd(',', ';', ':', ' ').trim() +
            if (out.endsWith('.') || out.endsWith('!') || out.endsWith('?')) "" else "."
    }

    /** Parte texto largo en trozos seguros para motores TTS (Samsung/Google). */
    fun chunkForTts(text: String, maxChunkChars: Int = TTS_CHUNK_CHARS): List<String> {
        val t = text.trim()
        if (t.isEmpty()) return emptyList()
        if (t.length <= maxChunkChars) return listOf(t)

        val chunks = mutableListOf<String>()
        var remaining = t
        while (remaining.isNotEmpty()) {
            if (remaining.length <= maxChunkChars) {
                chunks.add(remaining)
                break
            }
            val window = remaining.take(maxChunkChars)
            val splitAt = listOf(
                window.lastIndexOf(". "),
                window.lastIndexOf("? "),
                window.lastIndexOf("! "),
                window.lastIndexOf("… "),
                window.lastIndexOf("; "),
                window.lastIndexOf(", "),
                window.lastIndexOf(' '),
            ).filter { it >= maxChunkChars / 3 }.maxOrNull()

            if (splitAt == null) {
                chunks.add(window)
                remaining = remaining.drop(maxChunkChars).trimStart()
            } else {
                val end = splitAt + 1
                chunks.add(remaining.take(end).trim())
                remaining = remaining.drop(end).trimStart()
            }
        }
        return chunks.filter { it.isNotBlank() }
    }

    private const val BRIEF_MAX_CHARS = 220
    private const val BRIEF_MAX_SENTENCES = 2
    private const val TTS_CHUNK_CHARS = 350
}
