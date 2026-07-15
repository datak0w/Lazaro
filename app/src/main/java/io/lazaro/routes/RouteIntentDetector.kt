package io.lazaro.routes

import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

enum class RouteIntent {
    START_RECORDING,
    STOP_RECORDING,
    LIST_ROUTES,
    DELETE_ROUTE,
    ROUTE_DETAILS,
}

@Singleton
class RouteIntentDetector @Inject constructor() {

    fun detect(userText: String): RouteIntent? {
        val text = normalize(userText)
        if (text.isBlank()) return null
        return when {
            STOP_TRIGGERS.any { text.contains(it) } -> RouteIntent.STOP_RECORDING
            START_TRIGGERS.any { text.contains(it) } -> RouteIntent.START_RECORDING
            LIST_TRIGGERS.any { text.contains(it) } -> RouteIntent.LIST_ROUTES
            DELETE_TRIGGERS.any { text.contains(it) } -> RouteIntent.DELETE_ROUTE
            DETAIL_TRIGGERS.any { text.contains(it) } -> RouteIntent.ROUTE_DETAILS
            else -> null
        }
    }

    fun extractRouteName(userText: String): String? {
        val text = normalize(userText)
        val patterns = listOf(
            Regex("""graba(?:r)?\s+ruta\s+(?:a|hacia|de)\s+(.+)"""),
            Regex("""ruta\s+(?:a|de)\s+(.+)"""),
            Regex("""detalles?\s+(?:de\s+)?(?:la\s+)?ruta\s+(?:a|de)\s+(.+)"""),
            Regex("""borra(?:r)?\s+(?:la\s+)?ruta\s+(?:a|de)\s+(.+)"""),
        )
        for (pattern in patterns) {
            val match = pattern.find(text) ?: continue
            val name = match.groupValues[1].trim()
            if (name.isNotBlank() && name !in STOP_WORDS) return name
        }
        if (text.contains("casa")) return "casa"
        return null
    }

    fun extractDestinationKey(userText: String): String? {
        val text = normalize(userText)
        return when {
            text.contains("casa") -> "casa"
            text.contains("trabajo") -> "trabajo"
            else -> null
        }
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
        private val STOP_WORDS = setOf("grabando", "activa", "ahora")

        private val START_TRIGGERS = listOf(
            "graba ruta", "grabar ruta", "empieza a grabar", "inicia grabacion",
            "inicia grabación", "grabando ruta", "nueva ruta",
        )

        private val STOP_TRIGGERS = listOf(
            "para de grabar", "parar de grabar", "termina de grabar",
            "finaliza grabacion", "finaliza grabación", "detener grabacion",
        )

        private val LIST_TRIGGERS = listOf(
            "mis rutas", "que rutas tengo", "qué rutas tengo", "rutas guardadas",
            "lista de rutas", "cuantas rutas", "cuántas rutas",
        )

        private val DELETE_TRIGGERS = listOf(
            "borra la ruta", "borrar ruta", "elimina la ruta", "eliminar ruta",
        )

        private val DETAIL_TRIGGERS = listOf(
            "detalles de la ruta", "detalle de la ruta", "info de la ruta",
            "informacion de la ruta", "información de la ruta",
        )
    }
}
