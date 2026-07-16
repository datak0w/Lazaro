package io.lazaro.pathguide

enum class RoadSide {
    LEFT,
    RIGHT,
    UNKNOWN,
}

enum class SidewalkAlignment {
    ON_SIDEWALK,
    DRIFTING_TO_ROAD,
    ON_ROAD,
    UNKNOWN,
}

enum class MapsInstructionType {
    TURN,
    STRAIGHT,
    CROSS_STREET,
    ROUNDABOUT,
    ARRIVE,
    OTHER,
}

enum class OutdoorNavPhase {
    FOLLOW_SIDEWALK,
    APPROACH_CROSSING,
    CROSSING,
    TURN_AT_JUNCTION,
    ARRIVING,
    DRIFT_WARNING,
}

data class StreetLayoutState(
    val roadSide: RoadSide = RoadSide.UNKNOWN,
    val safeSide: RoadSide = RoadSide.UNKNOWN,
    val alignment: SidewalkAlignment = SidewalkAlignment.UNKNOWN,
    val leftWallScore: Float = 0f,
    val rightWallScore: Float = 0f,
    /** 0 = bien en acera, 1 = en calzada. */
    val driftScore: Float = 0f,
    /** Qué tan pegado al lado seguro (0–1). */
    val centeringScore: Float = 0.5f,
    /** Borde izquierdo de acera estimado (0–1, base imagen). */
    val sidewalkLeftNorm: Float = 0.22f,
    /** Borde derecho de acera estimado (0–1). */
    val sidewalkRightNorm: Float = 0.78f,
)

data class CrosswalkState(
    val detected: Boolean = false,
    val confidence: Float = 0f,
    val distanceMeters: Float = 0f,
)
