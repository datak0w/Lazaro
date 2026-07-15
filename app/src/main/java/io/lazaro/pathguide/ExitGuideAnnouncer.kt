package io.lazaro.pathguide

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExitGuideAnnouncer @Inject constructor() {

    private val lastSpokenMs = mutableMapOf<String, Long>()

    fun buildExitFoundCue(
        target: ExitTarget,
        opening: OpeningCandidate?,
        corridor: CorridorState,
    ): DoorwayVoiceCue? {
        val message = SpatialPhraseBuilder.exitFoundPhrase(target, opening, corridor) ?: return null
        return DoorwayVoiceCue(
            message = message,
            debounceMs = EXIT_FOUND_DEBOUNCE_MS,
            cueId = "exit_found_${target.side.name.lowercase()}",
        )
    }

    fun buildJunctionCue(
        junction: JunctionType,
        corridor: CorridorState,
    ): DoorwayVoiceCue? {
        val distance = SpatialPhraseBuilder.formatDistance(SpatialPhraseBuilder.distanceForCorridor(corridor))
        val message = when (junction) {
            JunctionType.T_LEFT -> "Bifurcación a $distance: camino abierto a tu izquierda."
            JunctionType.T_RIGHT -> "Bifurcación a $distance: camino abierto a tu derecha."
            JunctionType.T_BOTH -> "Bifurcación en T a $distance: caminos a izquierda y derecha."
            JunctionType.DEAD_END -> "Callejón sin salida a $distance."
            JunctionType.NONE -> return null
        }
        return DoorwayVoiceCue(
            message = message,
            debounceMs = JUNCTION_DEBOUNCE_MS,
            cueId = "junction_${junction.name.lowercase()}",
        )
    }

    fun buildApproachCue(
        label: String,
        approach: ApproachState,
        corridor: CorridorState,
    ): DoorwayVoiceCue? {
        if (!approach.announceReady) return null
        return DoorwayVoiceCue(
            message = SpatialPhraseBuilder.approachObjectPhrase(label, corridor, approach),
            debounceMs = APPROACH_DEBOUNCE_MS,
            cueId = "approach_$label",
        )
    }

    fun mergeVoiceCue(
        exitCue: DoorwayVoiceCue?,
        doorwayCue: DoorwayVoiceCue?,
        imuCue: DoorwayVoiceCue?,
        approachCue: DoorwayVoiceCue?,
        phase: ExitBrainPhase,
    ): DoorwayVoiceCue? {
        return when {
            doorwayCue != null && phase in DOORWAY_PHASES -> doorwayCue
            exitCue != null && phase == ExitBrainPhase.EXIT_FOUND -> exitCue
            imuCue != null && phase in IMU_PHASES -> imuCue
            approachCue != null && phase == ExitBrainPhase.BLOCKED -> approachCue
            else -> doorwayCue ?: exitCue ?: approachCue ?: imuCue
        }
    }

    fun shouldSpeak(cue: DoorwayVoiceCue): Boolean {
        val now = System.currentTimeMillis()
        val lastMs = lastSpokenMs[cue.cueId] ?: 0L
        if (now - lastMs < cue.debounceMs) return false
        lastSpokenMs[cue.cueId] = now
        return true
    }

    fun reset() {
        lastSpokenMs.clear()
    }

    companion object {
        private val IMU_PHASES = setOf(
            ExitBrainPhase.ALIGN,
            ExitBrainPhase.CENTERED,
            ExitBrainPhase.PASS,
        )
        private val DOORWAY_PHASES = setOf(
            ExitBrainPhase.ALIGN,
            ExitBrainPhase.CENTERED,
            ExitBrainPhase.PASS,
            ExitBrainPhase.EXIT_FOUND,
        )

        private const val EXIT_FOUND_DEBOUNCE_MS = 18_000L
        private const val JUNCTION_DEBOUNCE_MS = 20_000L
        private const val APPROACH_DEBOUNCE_MS = 12_000L
    }
}
