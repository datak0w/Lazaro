package io.lazaro.pathguide

import io.lazaro.navigation.TurnSide

object OutdoorPhraseBuilder {

    fun sidewalkGuidancePhrase(layout: StreetLayoutState): String? {
        if (layout.roadSide == RoadSide.UNKNOWN || layout.safeSide == RoadSide.UNKNOWN) return null
        val road = when (layout.roadSide) {
            RoadSide.LEFT -> "a tu izquierda"
            RoadSide.RIGHT -> "a tu derecha"
            RoadSide.UNKNOWN -> return null
        }
        val safe = when (layout.safeSide) {
            RoadSide.LEFT -> "a la izquierda"
            RoadSide.RIGHT -> "a la derecha"
            RoadSide.UNKNOWN -> return null
        }
        return "La calzada está $road. Camina pegado $safe."
    }

    fun driftWarningPhrase(layout: StreetLayoutState): String? {
        return when (layout.alignment) {
            SidewalkAlignment.DRIFTING_TO_ROAD ->
                "Cuidado, te acercas a la calzada. Vuelve a la acera."
            SidewalkAlignment.ON_ROAD ->
                "Estás en la calzada. Vuelve a la acera de inmediato."
            else -> null
        }
    }

    fun crossSearchPhrase(): String = "Ahora hay que cruzar la calle. Busco el paso de cebra."

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
        val distance = SpatialPhraseBuilder.formatDistance(
            SpatialPhraseBuilder.distanceForCorridor(corridor, mode = PathGuideMode.NAVEGACION),
        )
        val turn = when (turnSide) {
            TurnSide.LEFT -> "gira a la izquierda"
            TurnSide.RIGHT -> "gira a la derecha"
            TurnSide.U_TURN -> "da la vuelta"
            null -> when (junction) {
                JunctionType.T_LEFT -> "sigue por la izquierda"
                JunctionType.T_RIGHT -> "sigue por la derecha"
                else -> null
            }
        } ?: return null

        val roadHint = when (layout.roadSide) {
            RoadSide.LEFT -> " La calzada está a tu izquierda."
            RoadSide.RIGHT -> " La calzada está a tu derecha."
            RoadSide.UNKNOWN -> ""
        }
        return "Bifurcación a $distance: $turn.$roadHint Cuidado, no pises la calzada."
    }

    fun arrivalPhrase(): String = "Has llegado a tu destino."
}
