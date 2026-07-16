package io.lazaro.navigation

import io.lazaro.pathguide.MapsInstructionType
import java.text.Normalizer

object MapsNavigationParser {
    private val IGNORE_PATTERNS = listOf(
        "calculando",
        "buscando ruta",
        "ruta encontrada",
        "llegada estimada",
        "google maps",
        "navegacion pausada",
        "navegación pausada",
        "recalculando",
        "sin conexion",
        "sin conexión",
        "gps",
        "ubicacion",
        "ubicación",
    )

    private val ARRIVE_PATTERNS = listOf(
        "has llegado",
        "llegaste a tu destino",
        "has llegado a tu destino",
        "you have arrived",
        "arrived at",
    )

    private val CROSS_PATTERNS = listOf(
        "cruza", "cruce", "cross", "peatonal", "paso de cebra",
        "cruzar la calle", "cruzar la avenida", "cross the street",
        "crosswalk", "paso peatonal",
    )

    private val STRAIGHT_HINTS = listOf(
        "sigue recto", "continua recto", "continúa recto", "siga recto",
        "go straight", "head straight", "mantente en", "manténgase en",
    )

    private val TURN_HINTS = listOf(
        "gira", "gire", "turn", "siga", "sigue", "continúe", "continua",
        "tome", "incorporese", "incorpórese", "rotonda", "rotunda",
        "izquierda", "derecha", "recto", "u-turn", "retorno",
        "mantente", "manténgase", "salga", "salir",
    )

    private val ETA_PATTERNS = listOf(
        Regex("^\\d+\\s*min"),
        Regex("^\\d+\\s*h\\s*\\d+"),
        Regex("^\\d+\\s*km"),
        Regex("^\\d+\\s*m\\b"),
        Regex("^\\d+:\\d+"),
    )

    fun parse(title: String, text: String, bigText: String): String? {
        val candidates = listOf(bigText, text, title)
            .map { clean(it) }
            .filter { it.isNotBlank() }
            .distinct()

        for (candidate in candidates) {
            if (shouldIgnore(candidate)) continue
            if (isArrival(candidate)) {
                return formatForSpeech(candidate)
            }
            if (isEtaOrStatusUpdate(candidate)) continue
            if (!looksLikeNavigationInstruction(candidate)) continue
            return formatForSpeech(candidate)
        }
        return null
    }

    fun classifyInstruction(text: String): MapsInstructionType {
        val normalized = normalize(text)
        return when {
            ARRIVE_PATTERNS.any { normalized.contains(it) } -> MapsInstructionType.ARRIVE
            CROSS_PATTERNS.any { normalized.contains(it) } -> MapsInstructionType.CROSS_STREET
            normalized.contains("rotonda") || normalized.contains("rotunda") ||
                normalized.contains("roundabout") -> MapsInstructionType.ROUNDABOUT
            STRAIGHT_HINTS.any { normalized.contains(it) } -> MapsInstructionType.STRAIGHT
            looksLikeTurnInstruction(text) -> MapsInstructionType.TURN
            else -> MapsInstructionType.OTHER
        }
    }

    fun isArrival(text: String): Boolean {
        val normalized = normalize(text)
        return ARRIVE_PATTERNS.any { normalized.contains(it) }
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
        // U-turn antes que izq/der (Maps a veces dice "gira a la izquierda para dar la vuelta")
        if (normalized.contains("u-turn") ||
            normalized.contains("retorno") ||
            normalized.contains("media vuelta") ||
            normalized.contains("da la vuelta") ||
            normalized.contains("dar la vuelta") ||
            normalized.contains("cambio de sentido") ||
            normalized.contains("cambiar de sentido") ||
            normalized.contains("vuelta en u")
        ) {
            return TurnSide.U_TURN
        }
        return when {
            normalized.contains("izquierda") || normalized.contains("left") -> TurnSide.LEFT
            normalized.contains("derecha") || normalized.contains("right") -> TurnSide.RIGHT
            else -> null
        }
    }

    private fun shouldIgnore(text: String): Boolean {
        val normalized = normalize(text)
        return IGNORE_PATTERNS.any { normalized.contains(it) }
    }

    private fun isEtaOrStatusUpdate(text: String): Boolean {
        val normalized = normalize(text)
        if (ETA_PATTERNS.any { it.containsMatchIn(normalized) }) return true
        if (!looksLikeNavigationInstruction(text) && normalized.contains("min")) return true
        return false
    }

    private fun looksLikeNavigationInstruction(text: String): Boolean {
        val normalized = normalize(text)
        return looksLikeTurnInstruction(text) ||
            CROSS_PATTERNS.any { normalized.contains(it) } ||
            STRAIGHT_HINTS.any { normalized.contains(it) } ||
            isArrival(text)
    }

    private fun looksLikeTurnInstruction(text: String): Boolean {
        val normalized = normalize(text)
        return TURN_HINTS.any { normalized.contains(it) }
    }

    private fun formatForSpeech(raw: String): String {
        val trimmed = raw.trim().trimEnd('.')
        val lower = trimmed.lowercase()
        return when {
            isArrival(trimmed) -> "Has llegado a tu destino."
            lower.startsWith("gira") || lower.startsWith("gire") ->
                "Ahora, $trimmed"
            lower.startsWith("turn") ->
                "Ahora, $trimmed"
            lower.startsWith("en ") ->
                "Ahora gira $trimmed"
            CROSS_PATTERNS.any { normalize(trimmed).contains(it) } ->
                "Ahora, $trimmed"
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
