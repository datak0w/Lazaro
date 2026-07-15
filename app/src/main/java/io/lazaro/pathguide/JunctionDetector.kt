package io.lazaro.pathguide

class JunctionDetector {

    private var stableFrames = 0
    private var latchedType = JunctionType.NONE

    fun detect(corridor: CorridorState): JunctionType {
        val leftOpen = corridor.leftProximity < OPEN_THRESHOLD
        val centerOpen = corridor.centerProximity < OPEN_THRESHOLD
        val rightOpen = corridor.rightProximity < OPEN_THRESHOLD

        val leftBlocked = corridor.leftProximity > BLOCK_THRESHOLD
        val centerBlocked = corridor.centerProximity > BLOCK_THRESHOLD
        val rightBlocked = corridor.rightProximity > BLOCK_THRESHOLD

        val candidate = when {
            leftBlocked && centerBlocked && rightBlocked -> JunctionType.DEAD_END
            leftOpen && rightOpen && centerBlocked -> JunctionType.T_BOTH
            leftOpen && !rightOpen && (centerOpen || centerBlocked) -> JunctionType.T_LEFT
            rightOpen && !leftOpen && (centerOpen || centerBlocked) -> JunctionType.T_RIGHT
            else -> JunctionType.NONE
        }

        if (candidate == JunctionType.NONE) {
            stableFrames = 0
            if (latchedType != JunctionType.NONE) {
                stableFrames = -2
            }
            if (stableFrames <= -STABLE_FRAMES) {
                latchedType = JunctionType.NONE
            }
            return latchedType
        }

        if (candidate == latchedType) {
            stableFrames++
        } else {
            stableFrames = 1
        }

        if (stableFrames >= STABLE_FRAMES) {
            latchedType = candidate
        }

        return if (stableFrames >= STABLE_FRAMES) latchedType else JunctionType.NONE
    }

    fun reset() {
        stableFrames = 0
        latchedType = JunctionType.NONE
    }

    companion object {
        private const val OPEN_THRESHOLD = 0.20f
        private const val BLOCK_THRESHOLD = 0.32f
        private const val STABLE_FRAMES = 4
    }
}
