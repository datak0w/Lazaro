package io.lazaro.pathguide

import io.lazaro.navigation.TurnSide

object OutdoorPhraseBuilder {

    fun sidewalkGuidancePhrase(layout: StreetLayoutState): String? = null

    fun driftWarningPhrase(layout: StreetLayoutState): String? = null

    fun crossSearchPhrase(): String = "Ahora hay que cruzar. Busco el paso de cebra."

    fun crosswalkFoundPhrase(crosswalk: CrosswalkState): String? {
        if (!crosswalk.detected) return null
        val distance = SpatialPhraseBuilder.formatDistance(crosswalk.distanceMeters)
        return "Paso de cebra a $distance delante. Cruza con cuidado."
    }

    fun junctionTurnPhrase(
        junction: JunctionType,
        turnSide: TurnSide?,
        layout: StreetLayoutState,
        corridor: CorridorState,
    ): String? {
        val turn = when (turnSide) {
            TurnSide.LEFT -> "Gira a la izquierda."
            TurnSide.RIGHT -> "Gira a la derecha."
            TurnSide.U_TURN -> "Da la vuelta."
            null -> when (junction) {
                JunctionType.T_LEFT -> "Gira a la izquierda."
                JunctionType.T_RIGHT -> "Gira a la derecha."
                else -> null
            }
        } ?: return null
        return turn
    }

    fun arrivalPhrase(): String = "Has llegado a tu destino."
}
