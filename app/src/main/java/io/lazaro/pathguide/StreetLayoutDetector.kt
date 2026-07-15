package io.lazaro.pathguide

class StreetLayoutDetector {
    private var leftWallFrames = 0
    private var rightWallFrames = 0
    private var leftOpenFrames = 0
    private var rightOpenFrames = 0

    fun update(corridor: CorridorState): StreetLayoutState {
        if (corridor.leftProximity > WALL_THRESHOLD) {
            leftWallFrames++
            leftOpenFrames = 0
        } else if (corridor.leftProximity < OPEN_THRESHOLD) {
            leftOpenFrames++
            leftWallFrames = 0
        } else {
            leftWallFrames = (leftWallFrames - 1).coerceAtLeast(0)
            leftOpenFrames = (leftOpenFrames - 1).coerceAtLeast(0)
        }

        if (corridor.rightProximity > WALL_THRESHOLD) {
            rightWallFrames++
            rightOpenFrames = 0
        } else if (corridor.rightProximity < OPEN_THRESHOLD) {
            rightOpenFrames++
            rightWallFrames = 0
        } else {
            rightWallFrames = (rightWallFrames - 1).coerceAtLeast(0)
            rightOpenFrames = (rightOpenFrames - 1).coerceAtLeast(0)
        }

        val leftWall = leftWallFrames >= STABLE_FRAMES
        val rightWall = rightWallFrames >= STABLE_FRAMES
        val leftOpen = leftOpenFrames >= STABLE_FRAMES
        val rightOpen = rightOpenFrames >= STABLE_FRAMES

        val roadSide = when {
            leftWall && rightOpen -> RoadSide.LEFT
            rightWall && leftOpen -> RoadSide.RIGHT
            leftOpen && !rightOpen && corridor.leftProximity < corridor.rightProximity - 0.12f ->
                RoadSide.LEFT
            rightOpen && !leftOpen && corridor.rightProximity < corridor.leftProximity - 0.12f ->
                RoadSide.RIGHT
            else -> RoadSide.UNKNOWN
        }

        val safeSide = when (roadSide) {
            RoadSide.LEFT -> RoadSide.RIGHT
            RoadSide.RIGHT -> RoadSide.LEFT
            RoadSide.UNKNOWN -> RoadSide.UNKNOWN
        }

        val alignment = when {
            roadSide == RoadSide.LEFT && corridor.leftProximity > DRIFT_THRESHOLD ->
                if (corridor.leftProximity > ROAD_THRESHOLD) SidewalkAlignment.ON_ROAD
                else SidewalkAlignment.DRIFTING_TO_ROAD
            roadSide == RoadSide.RIGHT && corridor.rightProximity > DRIFT_THRESHOLD ->
                if (corridor.rightProximity > ROAD_THRESHOLD) SidewalkAlignment.ON_ROAD
                else SidewalkAlignment.DRIFTING_TO_ROAD
            leftWall || rightWall -> SidewalkAlignment.ON_SIDEWALK
            else -> SidewalkAlignment.UNKNOWN
        }

        return StreetLayoutState(
            roadSide = roadSide,
            safeSide = safeSide,
            alignment = alignment,
            leftWallScore = corridor.leftProximity,
            rightWallScore = corridor.rightProximity,
        )
    }

    fun reset() {
        leftWallFrames = 0
        rightWallFrames = 0
        leftOpenFrames = 0
        rightOpenFrames = 0
    }

    companion object {
        private const val WALL_THRESHOLD = 0.30f
        private const val OPEN_THRESHOLD = 0.15f
        private const val DRIFT_THRESHOLD = 0.22f
        private const val ROAD_THRESHOLD = 0.38f
        private const val STABLE_FRAMES = 5
    }
}
