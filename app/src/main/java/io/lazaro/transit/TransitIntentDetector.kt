package io.lazaro.transit

import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

sealed class TransitUserIntent {
    data class FindNearby(val mode: TransitMode) : TransitUserIntent()
    data class PlanRoute(val destination: String) : TransitUserIntent()
}

@Singleton
class TransitIntentDetector @Inject constructor() {

    fun detectIntent(userText: String): TransitUserIntent? {
        val text = normalize(userText)
        if (text.isBlank()) return null

        detectRouteDestination(text)?.let { destination ->
            return TransitUserIntent.PlanRoute(destination)
        }

        if (!matchesAny(text, NEARBY_TRIGGERS)) return null

        return TransitUserIntent.FindNearby(
            when {
                matchesAny(text, METRO_TRIGGERS) -> TransitMode.METRO
                matchesAny(text, TRAIN_TRIGGERS) -> TransitMode.TRAIN
                matchesAny(text, TRAM_TRIGGERS) -> TransitMode.TRAM
                matchesAny(text, BUS_TRIGGERS) -> TransitMode.BUS
                else -> TransitMode.ANY
            },
        )
    }

    /** @deprecated Usar [detectIntent] */
    fun detect(userText: String): TransitMode? {
        return (detectIntent(userText) as? TransitUserIntent.FindNearby)?.mode
    }

    private fun detectRouteDestination(text: String): String? {
        if (!matchesAny(text, ROUTE_TRIGGERS)) return null

        val patterns = listOf(
            Regex("""(?:ruta|planifica|planificar) (?:en )?(?:transporte publico|bus|metro|tren|tranvia)? (?:a|hasta|para|hacia) (.+)"""),
            Regex("""(?:como (?:llego|voy|ir)|llegar|ir) (?:en )?(?:transporte publico|bus|metro|tren|tranvia) (?:a|hasta|para|hacia) (.+)"""),
            Regex("""(?:transporte publico|bus|metro|tren) (?:a|hasta|para|hacia) (.+)"""),
            Regex("""(?:llegar a|ir a) (.+) (?:en bus|en metro|en tren|en transporte publico|en tranvia)"""),
        )

        for (pattern in patterns) {
            val match = pattern.find(text) ?: continue
            val destination = cleanDestination(match.groupValues.getOrNull(1).orEmpty())
            if (destination.isNotBlank()) return destination
        }
        return null
    }

    private fun cleanDestination(raw: String): String {
        return raw
            .replace(Regex("""\s+(en bus|en metro|en tren|en tranvia|en transporte publico)$"""), "")
            .replace(Regex("""^(?:la|el|a|hasta|para|hacia)\s+"""), "")
            .trim()
    }

    private fun matchesAny(text: String, triggers: List<String>): Boolean {
        return triggers.any { text.contains(it) }
    }

    private fun normalize(text: String): String {
        return Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace("lazaro", " ")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    companion object {
        private val ROUTE_TRIGGERS = listOf(
            "ruta en transporte", "ruta en bus", "ruta en metro", "ruta en tren",
            "planifica ruta", "planificar ruta", "como llego a", "como voy a",
            "como ir a", "transporte publico a", "transporte publico hasta",
            "ir en bus a", "ir en metro a", "llegar en bus", "llegar en metro",
            "lineas para ir", "que linea cojo", "que autobus cojo",
        )
        private val NEARBY_TRIGGERS = listOf(
            "parada mas cercana", "parada cercana",
            "parada de bus", "parada de autobus", "metro cercano", "estacion cercana",
            "donde cojo el bus", "donde cojo el metro", "autobus cerca", "bus cerca",
            "andén", "anden", "guíame a la parada", "guíame al metro",
            "find_transit",
        )
        private val BUS_TRIGGERS = listOf("bus", "autobus", "parada")
        private val METRO_TRIGGERS = listOf("metro", "subte", "subway")
        private val TRAIN_TRIGGERS = listOf("tren", "cercanias", "rodalies", "renfe")
        private val TRAM_TRIGGERS = listOf("tranvia", "tram")
    }
}
