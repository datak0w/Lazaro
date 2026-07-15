package io.lazaro.pathguide

data class TurnAlignmentState(
    val remainingDeg: Float = 0f,
    val turnedDeg: Float = 0f,
    val visualTargetDeg: Float = 0f,
    val aligned: Boolean = false,
    val voiceCue: DoorwayVoiceCue? = null,
)

enum class TurnAlignmentTier {
    ALIGNED,
    FINE_LEFT,
    COARSE_LEFT,
    FINE_RIGHT,
    COARSE_RIGHT,
}
