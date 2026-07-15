package io.lazaro.navigation

import io.lazaro.pathguide.CrosswalkState
import io.lazaro.pathguide.JunctionType
import io.lazaro.pathguide.MapsInstructionType
import io.lazaro.pathguide.OutdoorNavPhase
import io.lazaro.pathguide.SidewalkAlignment
import io.lazaro.pathguide.StreetLayoutState
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MapsVisionFusionCoordinator @Inject constructor(
    private val navigationAudioCoordinator: NavigationAudioCoordinator,
) {
    private var phase = OutdoorNavPhase.FOLLOW_SIDEWALK
    private var crossSearchUntilMs = 0L
    private var crossingCompletedMs = 0L

    fun reset() {
        phase = OutdoorNavPhase.FOLLOW_SIDEWALK
        crossSearchUntilMs = 0L
        crossingCompletedMs = 0L
    }

    fun currentPhase(): OutdoorNavPhase = phase

    fun isCrossingSearchActive(now: Long = System.currentTimeMillis()): Boolean {
        return now < crossSearchUntilMs
    }

    fun onMapsInstruction(type: MapsInstructionType, rawText: String) {
        val now = System.currentTimeMillis()
        when (type) {
            MapsInstructionType.CROSS_STREET -> {
                phase = OutdoorNavPhase.APPROACH_CROSSING
                crossSearchUntilMs = now + CROSS_SEARCH_MS
            }
            MapsInstructionType.TURN,
            MapsInstructionType.ROUNDABOUT,
            -> {
                if (navigationAudioCoordinator.isWithinTurnWindow(now)) {
                    phase = OutdoorNavPhase.TURN_AT_JUNCTION
                }
            }
            MapsInstructionType.ARRIVE -> phase = OutdoorNavPhase.ARRIVING
            MapsInstructionType.STRAIGHT -> {
                if (phase == OutdoorNavPhase.TURN_AT_JUNCTION) {
                    phase = OutdoorNavPhase.FOLLOW_SIDEWALK
                }
            }
            MapsInstructionType.OTHER -> Unit
        }
        navigationAudioCoordinator.onMapsContext(type, rawText)
    }

    fun update(
        streetLayout: StreetLayoutState,
        crosswalk: CrosswalkState,
        junction: JunctionType,
        now: Long = System.currentTimeMillis(),
    ): OutdoorNavPhase {
        when {
            streetLayout.alignment == SidewalkAlignment.DRIFTING_TO_ROAD ||
                streetLayout.alignment == SidewalkAlignment.ON_ROAD ->
                phase = OutdoorNavPhase.DRIFT_WARNING
            phase == OutdoorNavPhase.DRIFT_WARNING &&
                streetLayout.alignment == SidewalkAlignment.ON_SIDEWALK ->
                phase = OutdoorNavPhase.FOLLOW_SIDEWALK
        }

        if (phase == OutdoorNavPhase.APPROACH_CROSSING && crosswalk.detected && crosswalk.confidence >= 0.45f) {
            phase = OutdoorNavPhase.CROSSING
        }

        if (phase == OutdoorNavPhase.CROSSING && !crosswalk.detected && now - crossingCompletedMs > 2_000L) {
            phase = OutdoorNavPhase.FOLLOW_SIDEWALK
            crossSearchUntilMs = 0L
        }

        if (crosswalk.detected && phase == OutdoorNavPhase.CROSSING) {
            crossingCompletedMs = now
        }

        if (phase == OutdoorNavPhase.TURN_AT_JUNCTION && junction == JunctionType.NONE &&
            !navigationAudioCoordinator.isWithinTurnWindow(now)
        ) {
            phase = OutdoorNavPhase.FOLLOW_SIDEWALK
        }

        if (phase == OutdoorNavPhase.APPROACH_CROSSING && now > crossSearchUntilMs && !crosswalk.detected) {
            phase = OutdoorNavPhase.FOLLOW_SIDEWALK
        }

        return phase
    }

    companion object {
        private const val CROSS_SEARCH_MS = 15_000L
    }
}
