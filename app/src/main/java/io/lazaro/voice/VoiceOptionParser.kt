package io.lazaro.voice

import java.text.Normalizer

object VoiceOptionParser {

    fun parseIndex(text: String, maxOptions: Int = 5): Int? {
        val normalized = normalize(text)
        if (normalized.isBlank()) return null

        val wordMap = mapOf(
            "uno" to 0, "una" to 0, "primero" to 0, "primera" to 0,
            "dos" to 1, "segundo" to 1, "segunda" to 1,
            "tres" to 2, "tercero" to 2, "tercera" to 2,
            "cuatro" to 3, "cuarto" to 3, "cuarta" to 3,
            "cinco" to 4, "quinto" to 4, "quinta" to 4,
        )

        wordMap[normalized]?.let { return it.coerceAtMost(maxOptions - 1) }

        for (token in normalized.split(Regex("\\s+"))) {
            wordMap[token]?.let { return it.coerceAtMost(maxOptions - 1) }
        }

        Regex("""(?:opcion|numero|la|el)\s*(\d+)""").find(normalized)?.groupValues?.get(1)
            ?.toIntOrNull()
            ?.let { num ->
                if (num in 1..maxOptions) return num - 1
            }

        Regex("""(?:opcion|numero)\s+(uno|dos|tres|cuatro|cinco|primera|segunda|tercera|cuarta|quinta)""")
            .find(normalized)
            ?.groupValues
            ?.get(1)
            ?.let { wordMap[it] }
            ?.let { return it.coerceAtMost(maxOptions - 1) }

        Regex("""(?:la|el)\s+(primera|segunda|tercera|cuarta|quinta)""")
            .find(normalized)
            ?.groupValues
            ?.get(1)
            ?.let { wordMap[it] }
            ?.let { return it.coerceAtMost(maxOptions - 1) }

        normalized.toIntOrNull()?.let { num ->
            if (num in 1..maxOptions) return num - 1
        }

        return null
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
