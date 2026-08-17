package io.lazaro.navigation

/**
 * Estado vivo de la guía propia (destino, distancia, rumbo) para chat y referencias.
 */
data class NavigationGuidanceSnapshot(
    val label: String,
    val destLat: Double?,
    val destLng: Double?,
    val distanceMeters: Int?,
    val etaMinutes: Int? = null,
    val currentStreet: String? = null,
    val metersToNextManeuver: Int? = null,
    val action: BlindNavigationPhraseBuilder.Action?,
    val nextManeuverHint: String?,
    val lastTip: String?,
    val tipsActive: Boolean,
    val offRoute: Boolean = false,
) {
    fun describeBrief(): String {
        val name = label.ifBlank { "tu destino" }
        val parts = mutableListOf("Vas hacia $name.")
        if (offRoute) {
            parts.add("Parece que te saliste de la ruta.")
        }
        currentStreet?.takeIf { it.length >= 2 }?.let {
            parts.add("Vas por $it.")
        }
        distanceMeters?.let { d ->
            when {
                d <= 25 -> parts.add("Estás muy cerca, a unos $d metros.")
                d <= 100 -> parts.add("Quedan unos $d metros.")
                else -> parts.add("Quedan unos ${(d / 10) * 10} metros.")
            }
        }
        etaMinutes?.takeIf { it >= 1 }?.let {
            parts.add("Unos $it minutos.")
        }
        metersToNextManeuver?.takeIf { it in 5..400 }?.let { m ->
            when (action) {
                BlindNavigationPhraseBuilder.Action.TURN_LEFT ->
                    parts.add("En unos $m metros, gira a la izquierda.")
                BlindNavigationPhraseBuilder.Action.TURN_RIGHT ->
                    parts.add("En unos $m metros, gira a la derecha.")
                BlindNavigationPhraseBuilder.Action.U_TURN ->
                    parts.add("En unos $m metros, da la vuelta.")
                BlindNavigationPhraseBuilder.Action.CROSS ->
                    parts.add("En unos $m metros, cruza con cuidado.")
                BlindNavigationPhraseBuilder.Action.ARRIVE ->
                    parts.add("Estás a punto de llegar.")
                else -> Unit
            }
        } ?: when (action) {
            BlindNavigationPhraseBuilder.Action.TURN_LEFT ->
                parts.add("Ahora toca girar a la izquierda.")
            BlindNavigationPhraseBuilder.Action.TURN_RIGHT ->
                parts.add("Ahora toca girar a la derecha.")
            BlindNavigationPhraseBuilder.Action.U_TURN ->
                parts.add("Parece que vas en sentido contrario: da la vuelta.")
            BlindNavigationPhraseBuilder.Action.FORWARD ->
                parts.add("Sigue hacia adelante.")
            BlindNavigationPhraseBuilder.Action.CROSS ->
                parts.add("Hay que cruzar con cuidado.")
            BlindNavigationPhraseBuilder.Action.ARRIVE ->
                parts.add("Has llegado.")
            else -> Unit
        }
        nextManeuverHint?.let { parts.add(it) }
        return parts.joinToString(" ").trim()
    }
}
