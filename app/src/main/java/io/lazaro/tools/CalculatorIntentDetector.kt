package io.lazaro.tools

import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CalculatorIntentDetector @Inject constructor() {

    fun detect(userText: String): Boolean {
        val text = normalize(userText)
        if (text.isBlank()) return false
        if (TRIGGERS.any { text.contains(it) }) return true
        return EXPRESSION_PATTERN.containsMatchIn(text)
    }

    private fun normalize(text: String): String {
        return Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace("lazaro", " ")
            .replace("lázaro", " ")
            .replace(Regex("[^a-z0-9\\s+*/.,-]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    companion object {
        private val TRIGGERS = listOf(
            "cuanto es",
            "cuánto es",
            "calcula",
            "calculame",
            "calcúlame",
            "multiplica",
            "divide",
            "suma",
            "resta",
            "resultado de",
        )

        private val EXPRESSION_PATTERN = Regex(
            """\d+\s*(por|x|\*|mas|más|menos|entre|\+|-)\s*\d+""",
        )
    }
}
