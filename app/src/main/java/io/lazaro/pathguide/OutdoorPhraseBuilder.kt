package io.lazaro.pathguide

import io.lazaro.navigation.TurnSide

object OutdoorPhraseBuilder {

    fun sidewalkGuidancePhrase(layout: StreetLayoutState): String? = null

    fun driftWarningPhrase(layout: StreetLayoutState): String? = null

    fun crossSearchPhrase(): String = "Ahora tienes que cruzar. Busco el paso de cebra."

    fun crosswalkFoundPhrase(crosswalk: CrosswalkState): String? {
        if (!crosswalk.detected) return null
        val distance = SpatialPhraseBuilder.formatDistance(crosswalk.distanceMeters)
        val side = when {
            crosswalk.lateralBias <= -0.35f -> "a tu izquierda"
            crosswalk.lateralBias >= 0.35f -> "a tu derecha"
            else -> "delante"
        }
        return when {
            crosswalk.distanceMeters in 1f..12f ->
                "Paso de cebra $side. Cruza ahora con cuidado."
            crosswalk.lateralBias <= -0.35f || crosswalk.lateralBias >= 0.35f ->
                "Paso de cebra $side a $distance. Prepárate para cruzar."
            else ->
                "Paso de cebra a $distance delante. Cruza con cuidado."
        }
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
