package io.lazaro.navigation

import java.text.Normalizer

object MapsNavigationParser {
    private val IGNORE_PATTERNS = listOf(
        "calculando",
        "buscando ruta",
        "ruta encontrada",
        "has llegado",
        "llegaste a tu destino",
        "llegada estimada",
        "google maps",
        "navegacion pausada",
        "navegación pausada",
        "recalculando",
    )

    private val INSTRUCTION_HINTS = listOf(
        "gira", "gire", "turn", "continúe", "continua", "siga", "sigue",
        "tome", "incorporese", "incorpórese", "rotonda", "rotunda",
        "destino", "metros", " m ", "calle", "avenida", "plaza", "paseo",
        "carretera", "camino", "izquierda", "derecha", "recto", "u-turn",
    )

    fun parse(title: String, text: String, bigText: String): String? {
        val candidates = listOf(bigText, text, title)
            .map { clean(it) }
            .filter { it.isNotBlank() }
            .distinct()

        for (candidate in candidates) {
            if (shouldIgnore(candidate)) continue
            if (!looksLikeInstruction(candidate)) continue
            return formatForSpeech(candidate)
        }
        return null
    }

    fun isTurnInstruction(instruction: String): Boolean {
        val normalized = normalize(instruction)
        return normalized.contains("izquierda") ||
            normalized.contains("derecha") ||
            normalized.contains("left") ||
            normalized.contains("right") ||
            normalized.contains("u-turn") ||
            normalized.contains("retorno")
    }

    fun turnSide(instruction: String): TurnSide? {
        val normalized = normalize(instruction)
        return when {
            normalized.contains("izquierda") || normalized.contains("left") -> TurnSide.LEFT
            normalized.contains("derecha") || normalized.contains("right") -> TurnSide.RIGHT
            normalized.contains("u-turn") || normalized.contains("retorno") -> TurnSide.U_TURN
            else -> null
        }
    }

    private fun shouldIgnore(text: String): Boolean {
        val normalized = normalize(text)
        return IGNORE_PATTERNS.any { normalized.contains(it) }
    }

    private fun looksLikeInstruction(text: String): Boolean {
        val normalized = normalize(text)
        return INSTRUCTION_HINTS.any { normalized.contains(it) }
    }

    private fun formatForSpeech(raw: String): String {
        val trimmed = raw.trim().trimEnd('.')
        val lower = trimmed.lowercase()
        return when {
            lower.startsWith("gira") || lower.startsWith("gire") ->
                "Ahora, $trimmed"
            lower.startsWith("turn") ->
                "Ahora, $trimmed"
            lower.startsWith("en ") ->
                "Ahora gira $trimmed"
            else -> trimmed
        }
    }

    private fun clean(text: String): String {
        return text
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun normalize(text: String): String {
        val withoutAccents = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        return withoutAccents.lowercase()
    }
}

enum class TurnSide {
    LEFT,
    RIGHT,
    U_TURN,
}
