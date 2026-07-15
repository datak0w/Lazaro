package io.lazaro.pathguide

import io.lazaro.navigation.TurnSide
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class TurnAlignmentGuide @Inject constructor(
    private val rotationTracker: DeviceRotationTracker,
) {
    private var active = false
    private var lastTier = TurnAlignmentTier.ALIGNED
    private var lastTierMs = 0L
    private var alignedAnnounced = false

    private var navTurnSide: TurnSide? = null
    private var navTurnStartedMs = 0L

    fun reset() {
        active = false
        lastTier = TurnAlignmentTier.ALIGNED
        alignedAnnounced = false
        navTurnSide = null
        rotationTracker.clearBaseline()
    }

    fun onNavigationTurn(side: TurnSide) {
        navTurnSide = side
        navTurnStartedMs = System.currentTimeMillis()
        rotationTracker.markBaseline()
        active = true
        alignedAnnounced = false
        lastTier = TurnAlignmentTier.ALIGNED
    }

    fun updateDoorway(visualTargetDeg: Float, phase: DoorwayGuidePhase): TurnAlignmentState {
        if (phase == DoorwayGuidePhase.IDLE ||
            phase == DoorwayGuidePhase.APPROACHING ||
            phase == DoorwayGuidePhase.PASSING
        ) {
            if (phase == DoorwayGuidePhase.IDLE) reset()
            return idleState(visualTargetDeg)
        }

        if (!active) {
            rotationTracker.markBaseline()
            active = true
            alignedAnnounced = false
        }

        return fuseAndBuild(visualTargetDeg, mode = GuideMode.DOORWAY)
    }

    fun updateNavigation(
        side: TurnSide?,
        withinTurnWindow: Boolean,
        visualCorridorDeg: Float,
    ): TurnAlignmentState? {
        if (!withinTurnWindow || side == null) {
            navTurnSide = null
            return null
        }

        if (navTurnSide != side) {
            onNavigationTurn(side)
        }

        val expected = expectedNavigationDegrees(side)
        val turned = rotationTracker.yawDeltaDeg()
        val imuRemaining = expected - turned
        val remaining = imuRemaining * 0.70f + visualCorridorDeg * 0.30f

        return buildState(
            remainingDeg = remaining,
            turnedDeg = turned,
            visualTargetDeg = visualCorridorDeg,
            mode = GuideMode.NAVIGATION,
        )
    }

    private fun fuseAndBuild(visualTargetDeg: Float, mode: GuideMode): TurnAlignmentState {
        val turned = rotationTracker.yawDeltaDeg()
        val imuRemaining = visualTargetDeg - turned
        val remaining = imuRemaining * 0.50f + visualTargetDeg * 0.50f
        return buildState(remaining, turned, visualTargetDeg, mode)
    }

    private fun buildState(
        remainingDeg: Float,
        turnedDeg: Float,
        visualTargetDeg: Float,
        mode: GuideMode,
    ): TurnAlignmentState {
        val tier = tierFor(remainingDeg)
        val aligned = abs(remainingDeg) <= alignedThreshold(mode)

        if (aligned) {
            if (!alignedAnnounced) {
                alignedAnnounced = true
                lastTier = TurnAlignmentTier.ALIGNED
                lastTierMs = System.currentTimeMillis()
                return TurnAlignmentState(
                    remainingDeg = remainingDeg,
                    turnedDeg = turnedDeg,
                    visualTargetDeg = visualTargetDeg,
                    aligned = true,
                    voiceCue = alignedCue(mode),
                )
            }
            return TurnAlignmentState(
                remainingDeg = remainingDeg,
                turnedDeg = turnedDeg,
                visualTargetDeg = visualTargetDeg,
                aligned = true,
            )
        }

        alignedAnnounced = false
        val tierChanged = tier != lastTier
        val voiceCue = voiceForTier(tier, mode, remainingDeg, tierChanged)
        if (voiceCue != null) {
            lastTier = tier
            lastTierMs = System.currentTimeMillis()
        }

        return TurnAlignmentState(
            remainingDeg = remainingDeg,
            turnedDeg = turnedDeg,
            visualTargetDeg = visualTargetDeg,
            aligned = false,
            voiceCue = voiceCue,
        )
    }

    private fun tierFor(remainingDeg: Float): TurnAlignmentTier {
        return when {
            remainingDeg <= -COARSE_DEG -> TurnAlignmentTier.COARSE_LEFT
            remainingDeg <= -FINE_DEG -> TurnAlignmentTier.FINE_LEFT
            remainingDeg >= COARSE_DEG -> TurnAlignmentTier.COARSE_RIGHT
            remainingDeg >= FINE_DEG -> TurnAlignmentTier.FINE_RIGHT
            else -> TurnAlignmentTier.ALIGNED
        }
    }

    private fun voiceForTier(
        tier: TurnAlignmentTier,
        mode: GuideMode,
        remainingDeg: Float,
        tierChanged: Boolean,
    ): DoorwayVoiceCue? {
        val now = System.currentTimeMillis()
        if (!tierChanged && now - lastTierMs < tierDebounce(mode)) return null

        val magnitude = abs(remainingDeg)
        val message = when (tier) {
            TurnAlignmentTier.COARSE_LEFT -> if (magnitude > 22f) {
                "Gira a la izquierda."
            } else {
                "Gira un poco a la izquierda."
            }

            TurnAlignmentTier.FINE_LEFT -> "Un poco más a la izquierda."

            TurnAlignmentTier.COARSE_RIGHT -> if (magnitude > 22f) {
                "Gira a la derecha."
            } else {
                "Gira un poco a la derecha."
            }

            TurnAlignmentTier.FINE_RIGHT -> "Un poco más a la derecha."

            TurnAlignmentTier.ALIGNED -> null
        } ?: return null

        return DoorwayVoiceCue(
            message = message,
            debounceMs = tierDebounce(mode),
            cueId = "imu_${tier.name.lowercase()}",
        )
    }

    private fun alignedCue(mode: GuideMode): DoorwayVoiceCue {
        val message = when (mode) {
            GuideMode.DOORWAY -> "Perfecto. Ve adelante."
            GuideMode.NAVIGATION -> "Perfecto. Sigue recto."
        }
        return DoorwayVoiceCue(
            message = message,
            debounceMs = ALIGNED_DEBOUNCE_MS,
            cueId = "imu_aligned",
        )
    }

    private fun expectedNavigationDegrees(side: TurnSide): Float {
        return when (side) {
            TurnSide.LEFT -> -NAV_TURN_EXPECTED_DEG
            TurnSide.RIGHT -> NAV_TURN_EXPECTED_DEG
            TurnSide.U_TURN -> 155f
        }
    }

    private fun alignedThreshold(mode: GuideMode): Float {
        return when (mode) {
            GuideMode.DOORWAY -> DOORWAY_ALIGNED_DEG
            GuideMode.NAVIGATION -> NAV_ALIGNED_DEG
        }
    }

    private fun tierDebounce(mode: GuideMode): Long {
        return when (mode) {
            GuideMode.DOORWAY -> DOORWAY_TIER_DEBOUNCE_MS
            GuideMode.NAVIGATION -> NAV_TIER_DEBOUNCE_MS
        }
    }

    private fun idleState(visualTargetDeg: Float): TurnAlignmentState {
        return TurnAlignmentState(visualTargetDeg = visualTargetDeg)
    }

    private enum class GuideMode {
        DOORWAY,
        NAVIGATION,
    }

    companion object {
        private const val FINE_DEG = 5f
        private const val COARSE_DEG = 12f
        private const val DOORWAY_ALIGNED_DEG = 4.5f
        private const val NAV_ALIGNED_DEG = 14f
        private const val NAV_TURN_EXPECTED_DEG = 68f
        private const val DOORWAY_TIER_DEBOUNCE_MS = 3_500L
        private const val NAV_TIER_DEBOUNCE_MS = 4_500L
        private const val ALIGNED_DEBOUNCE_MS = 8_000L
    }
}
