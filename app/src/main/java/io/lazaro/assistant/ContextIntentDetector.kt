package io.lazaro.assistant

import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

enum class ContextIntent {
    REPEAT_OPTIONS,
    CANCEL_PENDING,
    PENDING_HELP,
    NEW_COMMAND,
    INTERRUPT,
}

@Singleton
class ContextIntentDetector @Inject constructor() {

    fun detect(userText: String, hasPending: Boolean): ContextIntent? {
        val text = normalize(stripWakeWord(userText))
        if (text.isBlank()) return null

        if (isInterruptCommand(text)) return ContextIntent.INTERRUPT
        if (isRepeatOptionsRequest(text)) return ContextIntent.REPEAT_OPTIONS
        if (!hasPending) return null

        return when {
            matchesAny(text, UNCERTAIN_TRIGGERS) -> ContextIntent.PENDING_HELP
            isCancelPhrase(text) -> ContextIntent.CANCEL_PENDING
            matchesAny(text, HELP_TRIGGERS) -> ContextIntent.PENDING_HELP
            matchesAny(text, NEW_COMMAND_TRIGGERS) || looksLikeNewCommand(text) ->
                ContextIntent.NEW_COMMAND
            else -> null
        }
    }

    fun stripWakeWord(text: String): String {
        val wake = io.lazaro.voice.WakeWordDetector.parse(text)
        return if (wake.detected) wake.command else text.trim()
    }

    fun isInterruptCommand(text: String): Boolean {
        val normalized = normalize(stripWakeWord(text))
        return matchesAny(normalized, INTERRUPT_TRIGGERS)
    }

    fun isNavigationStopPhrase(text: String): Boolean {
        val normalized = normalize(stripWakeWord(text))
        return matchesAny(normalized, NAVIGATION_STOP_TRIGGERS)
    }

    fun isCancelPhrase(text: String): Boolean {
        val normalized = normalize(stripWakeWord(text))
        return matchesAny(normalized, CANCEL_TRIGGERS) || normalized in CANCEL_EXACT
    }

    fun isRepeatOptionsRequest(text: String): Boolean {
        val normalized = normalize(stripWakeWord(text))
        if (normalized.isBlank()) return false

        if (matchesAny(normalized, REPEAT_TRIGGERS)) return true
        if (REPEAT_PATTERNS.any { it.containsMatchIn(normalized) }) return true

        val tokens = normalized.split(" ")
        if (tokens.any { it in REPEAT_VERBS }) {
            if (tokens.any { it.startsWith("opcion") } ||
                tokens.any { it in setOf("anterior", "otra", "vez", "dijiste", "decias", "decia") }
            ) {
                return true
            }
        }

        return normalized.contains("no entend") ||
            normalized.contains("no he entend") ||
            normalized.contains("que opciones") ||
            normalized.contains("cuales son") ||
            normalized.contains("cuales eran")
    }

    private fun looksLikeNewCommand(text: String): Boolean {
        if (text.contains("lazaro") && text.length > 12) return true
        return COMMAND_VERBS.any { verb -> text.startsWith("$verb ") || text.contains(" $verb ") }
    }

    private fun matchesAny(text: String, triggers: List<String>): Boolean {
        return triggers.any { text.contains(it) }
    }

    private fun normalize(text: String): String {
        return Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    companion object {
        private val REPEAT_VERBS = setOf(
            "repite", "repiteme", "repetir", "repetirme", "repetime",
        )

        private val REPEAT_PATTERNS = listOf(
            Regex("repite(me)?\\s+(las\\s+)?opciones"),
            Regex("vuelve\\s+a\\s+decir"),
            Regex("que\\s+(me\\s+)?dijiste"),
            Regex("que\\s+(me\\s+)?decias"),
            Regex("como\\s+era"),
            Regex("otra\\s+vez"),
            Regex("puedes\\s+repetir"),
        )

        private val REPEAT_TRIGGERS = listOf(
            "repite las opciones", "repiteme las opciones", "repite opciones",
            "repiteme opciones", "repiteme opciones", "repiteme las opciones",
            "que opciones", "cuales eran", "cuales son las opciones", "no entendi",
            "no he entendido", "no entiendo", "otra vez las opciones", "dime las opciones",
            "repetir opciones", "vuelve a decir", "repite lo anterior", "que dijiste",
            "que decias", "como era", "puedes repetir", "repetir por favor",
        )

        private val CANCEL_EXACT = setOf(
            "cancela", "cancelar", "olvida", "olvidalo", "dejalo", "paso",
        )

        private val CANCEL_TRIGGERS = listOf(
            "cancela", "cancelar", "olvida", "olvidalo", "dejalo", "deja lo",
            "paso", "no importa", "salir", "nada olvida", "cancela todo", "otro dia",
            "dejalo estar", "olvidate",
        )

        private val INTERRUPT_TRIGGERS = listOf(
            "para", "parar", "detente", "stop", "callate", "cállate", "silencio",
            "interrumpe", "interrumpir", "para todo", "para la accion",
        )

        private val NAVIGATION_STOP_TRIGGERS = listOf(
            "parar navegacion", "terminar navegacion", "cancelar ruta",
            "salir de maps", "cerrar maps", "cerrar navegacion", "fin de ruta",
        )

        private val HELP_TRIGGERS = listOf(
            "que tengo que decir", "que digo", "que respondes", "ayuda",
            "que esperas", "que necesitas", "en que quedamos", "que hago",
            "que debo decir", "como respondo",
        )

        private val UNCERTAIN_TRIGGERS = listOf(
            "no se", "no lo se", "no se cual", "no se que", "no estoy seguro",
        )

        private val NEW_COMMAND_TRIGGERS = listOf(
            "mejor otra cosa", "cambia de tema", "otra cosa", "nuevo comando",
            "olvidate de eso y", "dejalo y",
        )

        private val COMMAND_VERBS = listOf(
            "llama", "llamar", "navega", "ir a", "leeme", "lee", "busca",
            "pon", "abre", "manda", "escribe", "where", "donde estoy",
        )
    }
}
