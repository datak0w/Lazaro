package io.lazaro.tools

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

object VoiceCalculator {

    fun tryEvaluate(userText: String): String? {
        val text = normalize(userText)
        if (text.isBlank()) return null

        extractExpression(text)?.let { expr ->
            return evaluateExpression(expr)
        }
        return null
    }

    private fun extractExpression(text: String): String? {
        val cleaned = text
            .replace(Regex("""cuanto\s+es|cuánto\s+es|calcula|calculame|calcúlame|resultado\s+de"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

        val patterns = listOf(
            Regex("""(-?\d+(?:[.,]\d+)?)\s*(por|x|\*|multiplicado\s+por)\s*(-?\d+(?:[.,]\d+)?)"""),
            Regex("""(-?\d+(?:[.,]\d+)?)\s*(entre|dividido\s+entre|/)\s*(-?\d+(?:[.,]\d+)?)"""),
            Regex("""(-?\d+(?:[.,]\d+)?)\s*(mas|más|\+)\s*(-?\d+(?:[.,]\d+)?)"""),
            Regex("""(-?\d+(?:[.,]\d+)?)\s*(menos|-)\s*(-?\d+(?:[.,]\d+)?)"""),
        )

        for (pattern in patterns) {
            val match = pattern.find(cleaned) ?: continue
            val a = parseNumber(match.groupValues[1]) ?: continue
            val op = match.groupValues[2]
            val b = parseNumber(match.groupValues[3]) ?: continue
            return "$a ${opSymbol(op)} $b"
        }

        val bare = Regex("""(-?\d+(?:[.,]\d+)?)\s*([+*/x-])\s*(-?\d+(?:[.,]\d+)?)""").find(cleaned)
        if (bare != null) {
            val a = parseNumber(bare.groupValues[1]) ?: return null
            val op = bare.groupValues[2]
            val b = parseNumber(bare.groupValues[3]) ?: return null
            return "$a $op $b"
        }
        return null
    }

    private fun opSymbol(op: String): String = when (op) {
        "por", "x", "*", "multiplicado por" -> "*"
        "entre", "dividido entre", "/" -> "/"
        "mas", "más", "+" -> "+"
        "menos", "-" -> "-"
        else -> op
    }

    private fun evaluateExpression(expr: String): String? {
        val tokens = expr.split(" ").filter { it.isNotBlank() }
        if (tokens.size != 3) return null

        val left = tokens[0].toDoubleOrNull() ?: return null
        val op = tokens[1]
        val right = tokens[2].toDoubleOrNull() ?: return null

        val result = when (op) {
            "+" -> left + right
            "-" -> left - right
            "*" -> left * right
            "/" -> if (right == 0.0) return "No puedo dividir entre cero."
            else left / right
            else -> return null
        }

        return formatResult(result)
    }

    private fun formatResult(value: Double): String {
        val rounded = if (abs(value - value.toLong()) < 0.0001) {
            value.toLong().toString()
        } else {
            String.format(Locale.US, "%.2f", value).replace('.', ',')
        }
        return "Es $rounded."
    }

    private fun parseNumber(raw: String): Double? {
        return raw.replace(',', '.').toDoubleOrNull()
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
}
