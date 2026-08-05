package io.lazaro.voice

/**
 * Limpia texto para TTS: quita markdown y símbolos que el motor lee en voz alta
 * («asterisco asterisco», «almohadilla», etc.).
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
        // Espacios
        t = t.replace(Regex("""\s+"""), " ").trim()
        return t
    }
}
