package io.lazaro.voice

import java.text.Normalizer
import java.util.Locale

data class WakeWordMatch(
    val detected: Boolean,
    val command: String,
)

object WakeWordDetector {

    private val exactWakeWords = setOf(
        "lazaro",
        "lasaro",
        "lazzaro",
    )

    fun containsWakeWord(text: String): Boolean = parse(text).detected

    /** Detección estricta para escucha pasiva: evita falsos positivos. */
    fun containsConfidentWakeWord(text: String): Boolean {
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return false
        return tokens.any { isConfidentWakeToken(it) }
    }

    fun extractCommand(text: String): WakeWordMatch = parse(text)

    fun parse(text: String): WakeWordMatch {
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return WakeWordMatch(detected = false, command = "")

        for (index in tokens.indices) {
            if (isWakeWordToken(tokens[index])) {
                val command = tokens.drop(index + 1).joinToString(" ").trim()
                return WakeWordMatch(detected = true, command = command)
            }
        }
        return WakeWordMatch(detected = false, command = "")
    }

    private fun tokenize(text: String): List<String> {
        return normalize(text)
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
    }

    private fun isWakeWordToken(token: String): Boolean {
        val normalized = normalize(token)
        if (normalized in exactWakeWords) return true
        if (normalized.length !in 4..8) return false
        return levenshtein(normalized, "lazaro") <= 1
    }

    private fun isConfidentWakeToken(token: String): Boolean {
        val normalized = normalize(token)
        if (normalized in exactWakeWords) return true
        if (normalized.length !in 5..7) return false
        return levenshtein(normalized, "lazaro") <= 1
    }

    private fun normalize(text: String): String {
        val withoutAccents = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        return withoutAccents
            .lowercase(Locale.getDefault())
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .trim()
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        val costs = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            var last = i
            var current = i + 1
            for (j in b.indices) {
                val next = costs[j + 1]
                costs[j + 1] = minOf(
                    costs[j + 1] + 1,
                    current + 1,
                    last + if (a[i] == b[j]) 0 else 1,
                )
                last = current
                current = next
            }
        }
        return costs[b.length]
    }
}
