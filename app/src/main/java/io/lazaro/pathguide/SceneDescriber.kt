package io.lazaro.pathguide

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SceneDescriber @Inject constructor(
    private val bypassAdvisor: BypassAdvisor,
) {

    fun describe(snapshot: SceneSnapshot, mapsInstruction: String? = null): String {
        val parts = mutableListOf<String>()

        if (!mapsInstruction.isNullOrBlank()) {
            parts += "En ruta. $mapsInstruction"
        }

        when {
            snapshot.doorwayActive && snapshot.doorwayPhase != DoorwayGuidePhase.IDLE ->
                parts += doorwayPhrase(snapshot)
            snapshot.frontal.blocked -> parts += frontalPhrase(snapshot)
            else -> parts += pathPhrase(snapshot.corridor)
        }

        val objects = snapshot.labels.items
            .map { it.spanish }
            .filter { it !in REDUNDANT_OBJECTS }
            .distinct()
            .take(if (mapsInstruction.isNullOrBlank()) 3 else 1)

        if (objects.isNotEmpty() && mapsInstruction.isNullOrBlank()) {
            parts += formatObjectsSpatial(objects, snapshot.corridor)
        }

        lateralHints(snapshot.corridor)?.let { parts += it }

        return parts.distinct().joinToString(". ") + "."
    }

    private fun doorwayPhrase(snapshot: SceneSnapshot): String {
        val door = snapshot.corridor.doorway
        val distance = SpatialPhraseBuilder.formatDistance(
            SpatialPhraseBuilder.estimateMeters(
                approachFactor = door.approachFactor,
                openingWidthNorm = door.openingWidthNorm,
            ),
        )
        val position = SpatialPhraseBuilder.lateralOffsetPhrase(door.centerNorm - 0.5f)
        return when (snapshot.doorwayPhase) {
            DoorwayGuidePhase.APPROACHING -> "Veo una puerta a $distance, $position"
            DoorwayGuidePhase.TURN_LEFT ->
                "La puerta está $position, a $distance. Gira un poco a la izquierda"
            DoorwayGuidePhase.TURN_RIGHT ->
                "La puerta está $position, a $distance. Gira un poco a la derecha"
            DoorwayGuidePhase.ALIGNING ->
                "La puerta está $position, a $distance. Alinéate con el hueco"
            DoorwayGuidePhase.CENTERED -> "Puerta centrada a $distance, puedes avanzar"
            DoorwayGuidePhase.PASSING -> "Cruzando la puerta a $distance"
            DoorwayGuidePhase.IDLE -> pathPhrase(snapshot.corridor)
        }
    }

    private fun frontalPhrase(snapshot: SceneSnapshot): String {
        val label = snapshot.labels.primaryOrDefault()
        return SpatialPhraseBuilder.frontalObstaclePhrase(
            label = label,
            corridor = snapshot.corridor,
            advice = bypassAdvisor.advise(snapshot.corridor),
        )
    }

    private fun pathPhrase(corridor: CorridorState): String {
        val distance = SpatialPhraseBuilder.formatDistance(SpatialPhraseBuilder.distanceForCorridor(corridor))
        return when {
            corridor.isCentered -> "Camino despejado a $distance"
            corridor.leftProximity >= 0.38f && corridor.rightProximity >= 0.38f ->
                "Camino estrecho a $distance, avanza con cuidado"
            corridor.leftProximity >= 0.38f ->
                "Espacio libre a tu derecha a $distance"
            corridor.rightProximity >= 0.38f ->
                "Espacio libre a tu izquierda a $distance"
            else -> "Camino despejado a $distance"
        }
    }

    private fun lateralHints(corridor: CorridorState): String? {
        val hints = mutableListOf<String>()
        if (corridor.leftProximity >= 0.42f) {
            val d = SpatialPhraseBuilder.formatDistance(
                SpatialPhraseBuilder.estimateMeters(proximity = corridor.leftProximity),
            )
            hints += "pared u obstáculo a $d a tu izquierda"
        }
        if (corridor.rightProximity >= 0.42f) {
            val d = SpatialPhraseBuilder.formatDistance(
                SpatialPhraseBuilder.estimateMeters(proximity = corridor.rightProximity),
            )
            hints += "pared u obstáculo a $d a tu derecha"
        }
        if (hints.isEmpty()) return null
        return hints.joinToString(", ")
    }

    private fun formatObjectsSpatial(objects: List<String>, corridor: CorridorState): String {
        val primary = SpatialPhraseBuilder.seeObjectPhrase(objects.first(), corridor)
        if (objects.size == 1) return primary

        val others = objects.drop(1).joinToString(" y ") { SpatialPhraseBuilder.articleFor(it) }
        val side = SpatialPhraseBuilder.inferSide(corridor)
        val distance = SpatialPhraseBuilder.formatDistance(
            SpatialPhraseBuilder.distanceForCorridor(corridor) + 1f,
        )
        return "$primary. También veo $others a $distance $side"
    }

    companion object {
        private val REDUNDANT_OBJECTS = setOf(
            "puerta",
            "pared",
            "vía",
            "edificio",
            "obstáculo",
            "escaleras",
        )
    }
}
