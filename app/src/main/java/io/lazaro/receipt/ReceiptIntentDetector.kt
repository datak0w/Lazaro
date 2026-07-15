package io.lazaro.receipt

import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReceiptIntentDetector @Inject constructor() {

    fun detect(userText: String): Boolean {
        val text = normalize(userText)
        if (text.isBlank()) return false
        return TRIGGERS.any { text.contains(it) }
    }

    private fun normalize(text: String): String {
        return Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace("lazaro", " ")
            .replace("lázaro", " ")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    companion object {
        private val TRIGGERS = listOf(
            "comprueba el ticket",
            "comprueba ticket",
            "comprobar ticket",
            "revisa el ticket",
            "revisar ticket",
            "lee el ticket",
            "leer el ticket",
            "leeme el ticket",
            "leeme el recibo",
            "lee el recibo",
            "comprueba el recibo",
            "comprobar recibo",
            "revisa el recibo",
            "cuanto me cobran",
            "cuánto me cobran",
            "que me cobran",
            "qué me cobran",
            "no me enganen",
            "no me engañen",
            "que no me enganen",
            "verificar ticket",
            "verificar recibo",
            "escanea el ticket",
            "escanear ticket",
        )
    }
}
