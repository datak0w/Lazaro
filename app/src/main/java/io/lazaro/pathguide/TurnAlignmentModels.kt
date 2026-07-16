package io.lazaro.pathguide

data class TurnAlignmentState(
    val remainingDeg: Float = 0f,
    val turnedDeg: Float = 0f,
    val visualTargetDeg: Float = 0f,
    val aligned: Boolean = false,
    /** True only on the frame when alignment is first achieved. */
    val justAligned: Boolean = false,
    val voiceCue: DoorwayVoiceCue? = null,
    /** Pitido en el lado hacia el que debe girar (0–1). */
    val guideLeftBeep: Float = 0f,
    val guideRightBeep: Float = 0f,
    val continuousGuide: Boolean = false,
)

enum class TurnAlignmentTier {
    ALIGNED,
    FINE_LEFT,
    COARSE_LEFT,
    FINE_RIGHT,
    COARSE_RIGHT,
}
