package io.lazaro.pathguide

import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

enum class WalkIntent {
    START,
    STOP,
}

@Singleton
class WalkModeIntentDetector @Inject constructor() {

    fun detect(userText: String): WalkIntent? {
        val text = normalize(userText)
        if (text.isBlank()) return null
        if (matchesAny(text, STOP_TRIGGERS)) return WalkIntent.STOP
        if (matchesAny(text, START_TRIGGERS)) return WalkIntent.START
        return null
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
        private val START_TRIGGERS = listOf(
            "iniciar paseo",
            "modo paseo",
            "quiero pasear",
            "guia de paseo",
            "guía de paseo",
            "activar guia caminando",
            "activar guía caminando",
            "empezar paseo",
            "comenzar paseo",
        )

        private val STOP_TRIGGERS = listOf(
            "terminar paseo",
            "parar paseo",
            "detener paseo",
            "salir del paseo",
            "finalizar paseo",
            "acabar paseo",
        )
    }
}
