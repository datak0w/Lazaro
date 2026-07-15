package io.lazaro.pathguide

data class DoorwayState(
    val detected: Boolean = false,
    val confidence: Float = 0f,
    val leftJambNorm: Float = 0f,
    val rightJambNorm: Float = 1f,
    val centerNorm: Float = 0.5f,
    val openingWidthNorm: Float = 0f,
    val approachFactor: Float = 0f,
    val leftProximity: Float = 0f,
    val rightProximity: Float = 0f,
    val isCentered: Boolean = true,
)
