package io.lazaro.pathguide

import io.lazaro.navigation.BlindNavigationPhraseBuilder
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
        val remaining = imuRemaining * 0.75f + visualCorridorDeg * 0.25f

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
        val remaining = imuRemaining * 0.65f + visualTargetDeg * 0.35f
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
        val (guideLeft, guideRight, continuous) = guideBeepsFor(remainingDeg, aligned)

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
                    justAligned = true,
                    voiceCue = alignedCue(mode),
                    guideLeftBeep = 0f,
                    guideRightBeep = 0f,
                    continuousGuide = false,
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
            guideLeftBeep = guideLeft,
            guideRightBeep = guideRight,
            continuousGuide = continuous,
        )
    }

    /**
     * Pitido en el lado hacia el que debe girar.
     * Más fuerte y continuo cuanto mayor sea el error angular.
     */
    private fun guideBeepsFor(remainingDeg: Float, aligned: Boolean): Triple<Float, Float, Boolean> {
        if (aligned) return Triple(0f, 0f, false)
        val mag = abs(remainingDeg)
        if (mag < FINE_DEG) return Triple(0f, 0f, false)

        val strength = when {
            mag >= COARSE_DEG -> 0.92f
            mag >= FINE_DEG * 1.6f -> 0.75f
            else -> 0.58f
        }
        val continuous = mag >= FINE_DEG
        return if (remainingDeg < 0f) {
            Triple(strength, 0f, continuous)
        } else {
            Triple(0f, strength, continuous)
        }
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

        val message = BlindNavigationPhraseBuilder.imuTurnTip(remainingDeg, navTurnSide)
            ?: return null

        return DoorwayVoiceCue(
            message = message,
            debounceMs = tierDebounce(mode),
            cueId = "imu_${tier.name.lowercase()}",
        )
    }

    private fun alignedCue(mode: GuideMode): DoorwayVoiceCue {
        val message = when (mode) {
            GuideMode.DOORWAY -> "Perfecto. Camina hacia adelante."
            GuideMode.NAVIGATION -> "Perfecto. Camina hacia adelante."
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
        private const val NAV_ALIGNED_DEG = 12f
        private const val NAV_TURN_EXPECTED_DEG = 70f
        private const val DOORWAY_TIER_DEBOUNCE_MS = 4_000L
        private const val NAV_TIER_DEBOUNCE_MS = 5_000L
        private const val ALIGNED_DEBOUNCE_MS = 8_000L
    }
}
