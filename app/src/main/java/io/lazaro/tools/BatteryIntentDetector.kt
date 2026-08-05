package io.lazaro.tools

import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BatteryIntentDetector @Inject constructor() {
    fun detect(userText: String): Boolean {
        val n = normalize(userText)
        if (n.isBlank()) return false
        return TRIGGERS.any { n.contains(it) }
    }

    private fun normalize(text: String): String {
        val withoutAccents = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        return withoutAccents.lowercase().trim()
    }

    companion object {
        private val TRIGGERS = listOf(
            "que bateria",
            "bateria del baston",
            "bateria del movil",
            "bateria del telefono",
            "cuanta bateria",
            "nivel de bateria",
            "como esta la bateria",
            "estado de la bateria",
            "bateria restante",
            "porcentaje de bateria",
        )
    }
}
