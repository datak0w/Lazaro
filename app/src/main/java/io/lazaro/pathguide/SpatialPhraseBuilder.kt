package io.lazaro.pathguide

import kotlin.math.abs
import kotlin.math.max

object SpatialPhraseBuilder {

    fun estimateMeters(
        proximity: Float = 0f,
        approachFactor: Float = 0f,
        openingWidthNorm: Float = 0f,
        closeRange: Boolean = false,
        frontalSeverity: Float = 0f,
        mode: PathGuideMode = PathGuideMode.PASEO,
    ): Float {
        val widthCloseness = if (openingWidthNorm > 0f) {
            ((openingWidthNorm - 0.14f) / 0.36f).coerceIn(0f, 1f)
        } else {
            0f
        }
        val closeness = max(
            proximity,
            max(frontalSeverity, max(approachFactor, widthCloseness)),
        )

        val isOutdoor = mode == PathGuideMode.NAVEGACION || mode == PathGuideMode.PASEO
        return when {
            closeRange || closeness >= 0.82f -> if (isOutdoor) 1.5f else 1.0f
            closeness >= 0.68f -> if (isOutdoor) 2.0f else 1.5f
            closeness >= 0.52f -> if (isOutdoor) 3.0f else 2.0f
            closeness >= 0.38f -> if (isOutdoor) 4.5f else 3.0f
            closeness >= 0.24f -> if (isOutdoor) 5.5f else 4.5f
            closeness >= 0.12f -> if (isOutdoor) 6.0f else 6.0f
            else -> if (isOutdoor) 8.0f else 8.0f
        }
    }

    fun formatDistance(meters: Float): String {
        return when {
            meters < 1.3f -> "1 metro"
            meters < 1.8f -> "1 metro y medio"
            meters < 2.3f -> "2 metros"
            meters < 2.8f -> "2 metros y medio"
            meters < 3.5f -> "3 metros"
            meters < 4.5f -> "4 metros"
            meters < 5.5f -> "5 metros"
            else -> "${meters.toInt()} metros"
        }
    }

    fun lateralOffsetPhrase(offset: Float): String {
        return when {
            abs(offset) < 0.06f -> "al frente"
            offset < -0.22f -> "a tu izquierda"
            offset > 0.22f -> "a tu derecha"
            offset < 0f -> "al frente, ligeramente a la izquierda"
            else -> "al frente, ligeramente a la derecha"
        }
    }

    fun inferSide(corridor: CorridorState): String {
        return when {
            corridor.isFrontallyBlocked || corridor.centerProximity >= 0.28f -> "delante"
            corridor.leftProximity > corridor.rightProximity + 0.12f -> "a tu izquierda"
            corridor.rightProximity > corridor.leftProximity + 0.12f -> "a tu derecha"
            corridor.leftProximity >= 0.38f && corridor.rightProximity >= 0.38f -> "a ambos lados"
            else -> "delante"
        }
    }

    fun distanceForCorridor(
        corridor: CorridorState,
        doorway: DoorwayState? = null,
        mode: PathGuideMode = PathGuideMode.PASEO,
    ): Float {
        val door = doorway ?: corridor.doorway
        return estimateMeters(
            proximity = max(corridor.centerProximity, max(corridor.leftProximity, corridor.rightProximity)),
            approachFactor = door.approachFactor,
            openingWidthNorm = door.openingWidthNorm,
            closeRange = corridor.frontalCloseRange,
            frontalSeverity = corridor.frontalSeverity,
            mode = mode,
        )
    }

    fun seeObjectPhrase(
        label: String,
        corridor: CorridorState,
        doorway: DoorwayState? = null,
    ): String {
        val distance = formatDistance(distanceForCorridor(corridor, doorway))
        val side = inferSide(corridor)
        return "Veo ${articleFor(label)} a $distance $side"
    }

    fun approachObjectPhrase(
        label: String,
        corridor: CorridorState,
        approach: ApproachState,
    ): String {
        val distance = formatDistance(
            estimateMeters(
                proximity = corridor.centerProximity,
                approachFactor = corridor.doorway.approachFactor,
                openingWidthNorm = corridor.doorway.openingWidthNorm,
                closeRange = corridor.frontalCloseRange,
                frontalSeverity = corridor.frontalSeverity,
            ) * (1f - approach.velocity.coerceIn(0f, 0.35f)),
        )
        val side = inferSide(corridor)
        return "Te acercas a ${articleFor(label)} a $distance $side"
    }

    fun openingTypeLabel(type: OpeningType): String = when (type) {
        OpeningType.DOOR -> "puerta"
        OpeningType.ARCH -> "arco"
        OpeningType.CORRIDOR_END -> "salida del pasillo"
    }

    fun exitFoundPhrase(
        target: ExitTarget,
        opening: OpeningCandidate?,
        corridor: CorridorState,
    ): String? {
        val side = target.side
        if (side == ExitSide.UNKNOWN && target.junction == JunctionType.NONE) return null

        val distance = formatDistance(distanceForCorridor(corridor, opening?.toDoorwayState()))
        return when {
            opening?.detected == true -> {
                val typeLabel = openingTypeLabel(opening.type)
                val offset = opening.centerNorm - 0.5f
                val position = lateralOffsetPhrase(offset)
                when (side) {
                    ExitSide.LEFT -> "Veo una $typeLabel a $distance a tu izquierda"
                    ExitSide.RIGHT -> "Veo una $typeLabel a $distance a tu derecha"
                    ExitSide.CENTER -> "Veo una $typeLabel a $distance, $position"
                    ExitSide.UNKNOWN -> "Veo una $typeLabel a $distance, $position"
                }
            }
            target.junction == JunctionType.T_LEFT ->
                "Bifurcación a $distance: camino abierto a tu izquierda"
            target.junction == JunctionType.T_RIGHT ->
                "Bifurcación a $distance: camino abierto a tu derecha"
            target.junction == JunctionType.T_BOTH ->
                "Bifurcación en T a $distance: caminos a izquierda y derecha"
            target.junction == JunctionType.DEAD_END ->
                "Callejón sin salida a $distance"
            side == ExitSide.LEFT -> "Veo una salida a $distance a tu izquierda"
            side == ExitSide.RIGHT -> "Veo una salida a $distance a tu derecha"
            else -> "Veo una salida a $distance delante"
        }
    }

    fun doorwayApproachPhrase(door: DoorwayState): String {
        val distance = formatDistance(
            estimateMeters(
                approachFactor = door.approachFactor,
                openingWidthNorm = door.openingWidthNorm,
            ),
        )
        val position = lateralOffsetPhrase(door.centerNorm - 0.5f)
        return "Veo una puerta a $distance, $position"
    }

    fun doorwayTurnPhrase(offset: Float, turnLeft: Boolean): String {
        val position = lateralOffsetPhrase(offset)
        return if (turnLeft) {
            "La puerta está $position. Gira un poco a la izquierda."
        } else {
            "La puerta está $position. Gira un poco a la derecha."
        }
    }

    fun doorwayAlignPhrase(offset: Float, turnLeft: Boolean): String {
        val position = lateralOffsetPhrase(offset)
        return if (turnLeft) {
            "La puerta está $position. Gira un poco más a la izquierda."
        } else {
            "La puerta está $position. Gira un poco más a la derecha."
        }
    }

    fun doorwayCenteredPhrase(door: DoorwayState): String {
        val distance = formatDistance(
            estimateMeters(
                approachFactor = door.approachFactor,
                openingWidthNorm = door.openingWidthNorm,
                proximity = 0.5f,
            ),
        )
        return "Perfecto. La puerta está centrada a $distance. Ve adelante."
    }

    fun frontalObstaclePhrase(
        label: String,
        corridor: CorridorState,
        advice: BypassAdvice,
        mode: PathGuideMode = PathGuideMode.PASEO,
    ): String {
        val distance = formatDistance(distanceForCorridor(corridor, mode = mode))
        val side = inferSide(corridor)
        val objectPhrase = "Veo ${articleFor(label)} a $distance $side"
        return when (advice.side) {
            BypassSide.STOP -> "$objectPhrase. Detente."
            BypassSide.LEFT -> "$objectPhrase. Esquiva por la izquierda."
            BypassSide.RIGHT -> "$objectPhrase. Esquiva por la derecha."
            BypassSide.CAUTIOUS_LEFT -> "$objectPhrase. Con calma, por la izquierda."
            BypassSide.CAUTIOUS_RIGHT -> "$objectPhrase. Con calma, por la derecha."
        }
    }

    fun articleFor(noun: String): String {
        return when (noun.lowercase()) {
            "persona" -> "una persona"
            "escaleras" -> "unas escaleras"
            "vegetación" -> "vegetación"
            "equipaje" -> "equipaje"
            "vía" -> "una vía"
            "sofá", "sofa" -> "un sofá"
            "silla" -> "una silla"
            "mesa" -> "una mesa"
            "mueble" -> "un mueble"
            "puerta" -> "una puerta"
            "animal" -> "un animal"
            "coche" -> "un coche"
            "bicicleta" -> "una bicicleta"
            "obstáculo" -> "un obstáculo"
            else -> {
                if (noun.startsWith("un ") || noun.startsWith("una ")) noun
                else "un $noun"
            }
        }
    }
}
