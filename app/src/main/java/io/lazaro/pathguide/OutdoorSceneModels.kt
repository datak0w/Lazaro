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
)

data class CrosswalkState(
    val detected: Boolean = false,
    val confidence: Float = 0f,
    val distanceMeters: Float = 0f,
)
