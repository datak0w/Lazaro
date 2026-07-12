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
        val text = normalize(userText)
        if (text.isBlank()) return null

        if (isInterruptCommand(text)) return ContextIntent.INTERRUPT
        if (matchesAny(text, REPEAT_TRIGGERS)) return ContextIntent.REPEAT_OPTIONS
        if (!hasPending) return null

        return when {
            matchesAny(text, CANCEL_TRIGGERS) -> ContextIntent.CANCEL_PENDING
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
        val normalized = normalize(text)
        return matchesAny(normalized, INTERRUPT_TRIGGERS)
    }

    fun isCancelPhrase(text: String): Boolean {
        val normalized = normalize(text)
        return matchesAny(normalized, CANCEL_TRIGGERS)
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
        private val REPEAT_TRIGGERS = listOf(
            "repite las opciones", "repiteme las opciones", "repite opciones",
            "que opciones", "cuales eran", "cuales son las opciones", "no entendi",
            "no he entendido", "otra vez las opciones", "dime las opciones",
            "repetir opciones", "vuelve a decir", "repite lo anterior",
        )
        private val CANCEL_TRIGGERS = listOf(
            "cancela", "cancelar", "olvida", "olvidalo", "dejalo", "deja lo",
            "paso", "no importa", "salir", "nada olvida", "cancela todo", "otro dia",
        )
        private val INTERRUPT_TRIGGERS = listOf(
            "para", "parar", "detente", "stop", "callate", "cállate", "silencio",
            "interrumpe", "interrumpir", "para todo", "para la accion",
        )
        private val HELP_TRIGGERS = listOf(
            "que tengo que decir", "que digo", "que respondes", "ayuda",
            "que esperas", "que necesitas", "en que quedamos",
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
