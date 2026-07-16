package io.lazaro.pathguide

/**
 * Detecta fachada vs calzada y si la persona se sale de la acera.
 *
 * - Lado con más ocupación/pared = **seguro** (fachada).
 * - Lado abierto = **calzada**.
 * - Deriva = pierdes contacto con la fachada (se baja la proximidad del lado seguro).
 */
class StreetLayoutDetector {
    private var leftWallFrames = 0
    private var rightWallFrames = 0
    private var leftOpenFrames = 0
    private var rightOpenFrames = 0
    private var lastRoadSide = RoadSide.UNKNOWN
    private var roadStableFrames = 0

    fun update(corridor: CorridorState): StreetLayoutState {
        updateWallCounters(corridor)

        val leftWall = leftWallFrames >= STABLE_FRAMES
        val rightWall = rightWallFrames >= STABLE_FRAMES
        val leftOpen = leftOpenFrames >= OPEN_STABLE
        val rightOpen = rightOpenFrames >= OPEN_STABLE

        val rawRoadSide = when {
            // Fachada izquierda, abierto derecha → calzada a la derecha
            leftWall && rightOpen -> RoadSide.RIGHT
            rightWall && leftOpen -> RoadSide.LEFT
            corridor.leftProximity > corridor.rightProximity + ASYM_MARGIN &&
                corridor.leftProximity > WALL_SOFT -> RoadSide.RIGHT
            corridor.rightProximity > corridor.leftProximity + ASYM_MARGIN &&
                corridor.rightProximity > WALL_SOFT -> RoadSide.LEFT
            leftOpen && !rightOpen -> RoadSide.LEFT
            rightOpen && !leftOpen -> RoadSide.RIGHT
            else -> RoadSide.UNKNOWN
        }

        val roadSide = stabilizeRoadSide(rawRoadSide)
        val safeSide = when (roadSide) {
            RoadSide.LEFT -> RoadSide.RIGHT
            RoadSide.RIGHT -> RoadSide.LEFT
            RoadSide.UNKNOWN -> RoadSide.UNKNOWN
        }

        val safeProximity = when (safeSide) {
            RoadSide.LEFT -> corridor.leftProximity
            RoadSide.RIGHT -> corridor.rightProximity
            RoadSide.UNKNOWN -> maxOf(corridor.leftProximity, corridor.rightProximity)
        }
        val roadOpenness = when (roadSide) {
            RoadSide.LEFT -> 1f - corridor.leftProximity
            RoadSide.RIGHT -> 1f - corridor.rightProximity
            RoadSide.UNKNOWN -> 0.5f
        }

        // Perder la fachada = riesgo de calzada.
        val driftScore = when {
            roadSide == RoadSide.UNKNOWN -> 0f
            safeProximity < 0.10f && roadOpenness > 0.70f -> 1.0f
            safeProximity < 0.14f && roadOpenness > 0.60f -> 0.82f
            safeProximity < 0.18f -> 0.55f
            safeProximity < 0.24f && roadOpenness > 0.55f -> 0.38f
            else -> 0f
        }

        val alignment = when {
            driftScore >= 0.80f -> SidewalkAlignment.ON_ROAD
            driftScore >= 0.30f -> SidewalkAlignment.DRIFTING_TO_ROAD
            roadSide != RoadSide.UNKNOWN || leftWall || rightWall ->
                SidewalkAlignment.ON_SIDEWALK
            else -> SidewalkAlignment.UNKNOWN
        }

        val centeringScore = when (safeSide) {
            RoadSide.UNKNOWN -> 0.5f
            else -> (safeProximity * 0.65f + (1f - driftScore) * 0.35f).coerceIn(0f, 1f)
        }

        val (leftEdge, rightEdge) = estimateSidewalkEdges(
            safeSide = safeSide,
            centeringScore = centeringScore,
            driftScore = driftScore,
        )

        return StreetLayoutState(
            roadSide = roadSide,
            safeSide = safeSide,
            alignment = alignment,
            leftWallScore = corridor.leftProximity,
            rightWallScore = corridor.rightProximity,
            driftScore = driftScore,
            centeringScore = centeringScore,
            sidewalkLeftNorm = leftEdge,
            sidewalkRightNorm = rightEdge,
        )
    }

    fun reset() {
        leftWallFrames = 0
        rightWallFrames = 0
        leftOpenFrames = 0
        rightOpenFrames = 0
        lastRoadSide = RoadSide.UNKNOWN
        roadStableFrames = 0
    }

    /**
     * Líneas de acera en perspectiva (coords normalizadas en la base de la ROI).
     * Se desplazan hacia la calzada si hay deriva o pierdes la fachada.
     */
    private fun estimateSidewalkEdges(
        safeSide: RoadSide,
        centeringScore: Float,
        driftScore: Float,
    ): Pair<Float, Float> {
        val targetCenter = when (safeSide) {
            RoadSide.LEFT -> 0.36f
            RoadSide.RIGHT -> 0.64f
            RoadSide.UNKNOWN -> 0.50f
        }
        val roadPull = when (safeSide) {
            RoadSide.LEFT -> driftScore * 0.20f
            RoadSide.RIGHT -> -driftScore * 0.20f
            RoadSide.UNKNOWN -> 0f
        }
        val offCenter = when (safeSide) {
            RoadSide.LEFT -> (1f - centeringScore) * 0.14f
            RoadSide.RIGHT -> -(1f - centeringScore) * 0.14f
            RoadSide.UNKNOWN -> 0f
        }
        val center = (targetCenter + roadPull + offCenter).coerceIn(0.28f, 0.72f)
        val half = (0.30f - driftScore * 0.05f).coerceIn(0.22f, 0.34f)
        return (center - half).coerceIn(0.04f, 0.48f) to
            (center + half).coerceIn(0.52f, 0.96f)
    }

    private fun updateWallCounters(corridor: CorridorState) {
        if (corridor.leftProximity > WALL_THRESHOLD) {
            leftWallFrames = (leftWallFrames + 1).coerceAtMost(12)
            leftOpenFrames = 0
        } else if (corridor.leftProximity < OPEN_THRESHOLD) {
            leftOpenFrames = (leftOpenFrames + 1).coerceAtMost(12)
            leftWallFrames = 0
        } else {
            leftWallFrames = (leftWallFrames - 1).coerceAtLeast(0)
            leftOpenFrames = (leftOpenFrames - 1).coerceAtLeast(0)
        }

        if (corridor.rightProximity > WALL_THRESHOLD) {
            rightWallFrames = (rightWallFrames + 1).coerceAtMost(12)
            rightOpenFrames = 0
        } else if (corridor.rightProximity < OPEN_THRESHOLD) {
            rightOpenFrames = (rightOpenFrames + 1).coerceAtMost(12)
            rightWallFrames = 0
        } else {
            rightWallFrames = (rightWallFrames - 1).coerceAtLeast(0)
            rightOpenFrames = (rightOpenFrames - 1).coerceAtLeast(0)
        }
    }

    private fun stabilizeRoadSide(raw: RoadSide): RoadSide {
        if (raw == RoadSide.UNKNOWN) {
            roadStableFrames = (roadStableFrames - 1).coerceAtLeast(0)
            return if (roadStableFrames > 0) lastRoadSide else RoadSide.UNKNOWN
        }
        if (raw == lastRoadSide) {
            roadStableFrames = (roadStableFrames + 1).coerceAtMost(8)
        } else {
            lastRoadSide = raw
            roadStableFrames = 1
        }
        return lastRoadSide
    }

    companion object {
        private const val WALL_THRESHOLD = 0.26f
        private const val WALL_SOFT = 0.22f
        private const val OPEN_THRESHOLD = 0.14f
        private const val ASYM_MARGIN = 0.12f
        private const val STABLE_FRAMES = 3
        private const val OPEN_STABLE = 3
    }
}
