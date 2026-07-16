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
        if (closeRange) return CLOSE_RANGE_METERS

        val widthCloseness = if (openingWidthNorm > 0f) {
            ((openingWidthNorm - 0.12f) / 0.30f).coerceIn(0f, 1f)
        } else {
            0f
        }

        val closeness = max(
            proximity,
            max(frontalSeverity * 0.95f, max(approachFactor, widthCloseness)),
        ).coerceIn(0f, 1f)

        val indoorBoost = if (mode == PathGuideMode.NAVEGACION || mode == PathGuideMode.PASEO) {
            0f
        } else {
            0f
        }

        val base = when {
            closeness >= 0.78f -> 0.6f
            closeness >= 0.65f -> 0.9f
            closeness >= 0.52f -> 1.2f
            closeness >= 0.42f -> 1.6f
            closeness >= 0.34f -> 2.1f
            closeness >= 0.27f -> 2.8f
            closeness >= 0.20f -> 3.6f
            closeness >= 0.14f -> 4.5f
            closeness >= 0.08f -> 5.5f
            else -> 7.0f
        }

        val outdoorExtra = if (mode == PathGuideMode.NAVEGACION) 0.8f else 0f
        return (base + outdoorExtra - indoorBoost).coerceIn(0.5f, 12f)
    }

    fun formatDistance(meters: Float): String {
        return when {
            meters < 0.75f -> "menos de 1 metro"
            meters < 1.15f -> "1 metro"
            meters < 1.55f -> "1 metro y medio"
            meters < 2.05f -> "2 metros"
            meters < 2.55f -> "2 metros y medio"
            meters < 3.05f -> "3 metros"
            meters < 3.85f -> "3 metros y medio"
            meters < 4.65f -> "4 metros"
            meters < 5.45f -> "5 metros"
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
            corridor.isFrontallyBlocked ||
                corridor.frontalCloseRange ||
                corridor.centerProximity >= 0.24f ||
                corridor.frontalSeverity >= 0.30f -> "delante"
            corridor.leftProximity > corridor.rightProximity + 0.10f -> "a tu izquierda"
            corridor.rightProximity > corridor.leftProximity + 0.10f -> "a tu derecha"
            corridor.leftProximity >= 0.32f && corridor.rightProximity >= 0.32f -> "a ambos lados"
            else -> "delante"
        }
    }

    fun distanceForCorridor(
        corridor: CorridorState,
        doorway: DoorwayState? = null,
        mode: PathGuideMode = PathGuideMode.PASEO,
    ): Float {
        val door = doorway ?: corridor.doorway
        val side = inferSide(corridor)
        val proximity = proximityForSide(corridor, side)
        return estimateMeters(
            proximity = proximity,
            approachFactor = door.approachFactor,
            openingWidthNorm = door.openingWidthNorm,
            closeRange = corridor.frontalCloseRange || corridor.isFrontallyBlocked,
            frontalSeverity = corridor.frontalSeverity,
            mode = mode,
        )
    }

    fun objectDistanceForCorridor(
        corridor: CorridorState,
        doorway: DoorwayState? = null,
        mode: PathGuideMode = PathGuideMode.PASEO,
    ): Float {
        if (corridor.frontalCloseRange) return CLOSE_RANGE_METERS

        val door = doorway ?: corridor.doorway
        val side = inferSide(corridor)
        val proximity = proximityForSide(corridor, side)
        val blockedBoost = if (corridor.isFrontallyBlocked) 0.18f else 0f

        val meters = estimateMeters(
            proximity = (proximity + blockedBoost).coerceAtMost(1f),
            approachFactor = door.approachFactor,
            openingWidthNorm = door.openingWidthNorm,
            closeRange = corridor.isFrontallyBlocked && corridor.frontalSeverity >= 0.28f,
            frontalSeverity = corridor.frontalSeverity,
            mode = mode,
        )

        return if (corridor.isFrontallyBlocked || corridor.frontalSeverity >= 0.35f) {
            meters.coerceAtMost(1.8f)
        } else {
            meters
        }
    }

    private fun proximityForSide(corridor: CorridorState, side: String): Float {
        return when {
            side == "delante" -> max(
                corridor.centerProximity,
                max(corridor.frontalSeverity * 0.9f, max(corridor.leftProximity, corridor.rightProximity) * 0.65f),
            )
            side.contains("izquierda") -> max(corridor.leftProximity, corridor.centerProximity * 0.55f)
            side.contains("derecha") -> max(corridor.rightProximity, corridor.centerProximity * 0.55f)
            else -> max(
                corridor.centerProximity,
                max(corridor.leftProximity, corridor.rightProximity),
            )
        }
    }

    fun seeObjectPhrase(
        label: String,
        corridor: CorridorState,
        doorway: DoorwayState? = null,
    ): String {
        val distance = formatDistance(objectDistanceForCorridor(corridor, doorway))
        val side = inferSide(corridor)
        return "Veo ${articleFor(label)} a $distance $side"
    }

    fun approachObjectPhrase(
        label: String,
        corridor: CorridorState,
        approach: ApproachState,
    ): String {
        val base = objectDistanceForCorridor(corridor)
        val distance = formatDistance(base * (1f - approach.velocity.coerceIn(0f, 0.30f)))
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
                proximity = door.approachFactor.coerceAtLeast(0.35f),
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
                proximity = max(door.approachFactor, 0.55f),
                closeRange = door.approachFactor >= 0.55f,
            ),
        )
        return "Perfecto. La puerta está centrada a $distance. Ve adelante."
    }

    fun frontalObstaclePhrase(
        label: String = "obstáculo",
        corridor: CorridorState,
        advice: BypassAdvice,
        mode: PathGuideMode = PathGuideMode.PASEO,
    ): String {
        val distance = formatDistance(objectDistanceForCorridor(corridor, mode = mode))
        val objectPhrase = "Obstáculo delante a $distance"
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

    private const val CLOSE_RANGE_METERS = 0.55f
}
