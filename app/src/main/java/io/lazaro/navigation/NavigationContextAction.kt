package io.lazaro.navigation

import io.lazaro.actions.ActionResult
import io.lazaro.actions.LocationAction
import io.lazaro.memory.SavedPlaceRepository
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Preguntas durante navegación: perdido, rumbo, cuánto falta, referencias cercanas.
 */
@Singleton
class NavigationContextAction @Inject constructor(
    private val navigationSessionManager: NavigationSessionManager,
    private val ownNavigationGuide: OwnNavigationGuide,
    private val locationAction: LocationAction,
    private val savedPlaceRepository: SavedPlaceRepository,
) {
    fun detect(userText: String): Boolean {
        val t = normalize(userText)
        if (t.isBlank()) return false
        return TRIGGERS.any { t.contains(it) }
    }

    suspend fun handle(userText: String): ActionResult? {
        if (!detect(userText)) return null
        if (!navigationSessionManager.isNavigationActive() && !ownNavigationGuide.hasRoute()) {
            return ActionResult.Success(
                "No hay una navegación en curso. Di a dónde quieres ir o guarda un sitio como referencia.",
            )
        }
        val snap = ownNavigationGuide.snapshot()
        val t = normalize(userText)
        val parts = mutableListOf<String>()

        when {
            t.contains("donde estoy en la ruta") ||
                t.contains("dónde estoy en la ruta") ||
                t.contains("en que punto") ||
                t.contains("en qué punto") -> {
                parts.add(snap.describeBrief())
            }
            t.contains("cuanto falta") || t.contains("cuánto falta") ||
                t.contains("cuanta distancia") || t.contains("cuánta distancia") -> {
                val name = snap.label.ifBlank { "tu destino" }
                val d = snap.distanceMeters
                val eta = snap.etaMinutes
                parts.add(
                    when {
                        d == null -> "Vas hacia $name. Aún no tengo la distancia exacta."
                        d <= 25 -> "Estás muy cerca de $name, a unos $d metros."
                        eta != null ->
                            "Quedan unos ${if (d >= 100) (d / 10) * 10 else d} metros hasta $name. Unos $eta minutos."
                        else ->
                            "Quedan unos ${if (d >= 100) (d / 10) * 10 else d} metros hasta $name."
                    },
                )
                snap.metersToNextManeuver?.takeIf { it in 5..300 }?.let { m ->
                    snap.nextManeuverHint?.let { parts.add(it) }
                        ?: parts.add("El próximo cambio de rumbo está a unos $m metros.")
                }
            }
            t.contains("perd") || t.contains("desorient") || t.contains("confund") -> {
                parts.add(snap.describeBrief())
                if (snap.offRoute) {
                    parts.add("Te saliste un poco: espera a que recalcule o sigue la próxima indicación.")
                } else {
                    parts.add(
                        "No te preocupes: párate un momento, orienta el teléfono hacia adelante y sigue la próxima indicación.",
                    )
                }
            }
            else -> parts.add(snap.describeBrief())
        }

        val origin = locationAction.getCurrentLocation()
        if (origin != null &&
            (t.contains("referencia") || t.contains("cerca") || t.contains("perd") ||
                t.contains("donde estoy") || t.contains("dónde estoy"))
        ) {
            val nearby = savedPlaceRepository.findNearby(
                origin.latitude,
                origin.longitude,
                NEARBY_RADIUS_M,
            )
            val destName = snap.label
            val ref = nearby.firstOrNull {
                !it.displayName.equals(destName, ignoreCase = true)
            }
            if (ref != null) {
                val d = NavigationBearing.distanceMeters(
                    origin.latitude, origin.longitude, ref.latitude, ref.longitude,
                ).toInt().coerceAtLeast(5)
                val rounded = if (d <= 100) d else (d / 10) * 10
                parts.add(
                    "Como referencia, estás a unos $rounded metros de ${ref.displayName}.",
                )
            }
        }

        return ActionResult.Success(parts.joinToString(" ").trim())
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
        private const val NEARBY_RADIUS_M = 250.0
        private val TRIGGERS = listOf(
            "estoy perdido", "me he perdido", "me perdi", "me perdí",
            "desorientado", "desorientada", "estoy desorientado", "estoy desorientada",
            "hacia donde", "hacia dónde", "para donde", "para dónde",
            "donde voy", "dónde voy", "a donde voy", "a dónde voy",
            "cuanto falta", "cuánto falta", "cuanta distancia", "cuánta distancia",
            "donde estoy yendo", "dónde estoy yendo",
            "donde estoy en la ruta", "dónde estoy en la ruta",
            "en que punto", "en qué punto",
            "orientame", "oriéntame", "orientame otra vez", "repiteme el rumbo",
            "repite el rumbo", "como voy", "cómo voy",
            "referencia", "que hay cerca", "qué hay cerca", "sitios cerca",
        )
    }
}
