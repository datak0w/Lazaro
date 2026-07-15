package io.lazaro.pathguide

data class CorridorState(
    val leftProximity: Float = 0f,
    val centerProximity: Float = 0f,
    val rightProximity: Float = 0f,
    val isCentered: Boolean = true,
    val isFrontallyBlocked: Boolean = false,
    val frontalSeverity: Float = 0f,
    val frontalCloseRange: Boolean = false,
    val doorwayActive: Boolean = false,
    val doorway: DoorwayState = DoorwayState(),
)

enum class HandrailSide {
    LEFT,
    RIGHT,
    NONE,
    UNKNOWN,
}

data class StairState(
    val detected: Boolean = false,
    val confidence: Float = 0f,
    val horizontalPeaks: Int = 0,
    val regularSpacing: Boolean = false,
    val spacingConsistency: Float = 0f,
    val handrailSide: HandrailSide = HandrailSide.UNKNOWN,
)

enum class BypassSide {
    LEFT,
    RIGHT,
    STOP,
    CAUTIOUS_LEFT,
    CAUTIOUS_RIGHT,
}

data class BypassAdvice(
    val side: BypassSide,
    val leftProximity: Float,
    val rightProximity: Float,
)
