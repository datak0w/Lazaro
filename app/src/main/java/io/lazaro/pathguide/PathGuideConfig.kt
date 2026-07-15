package io.lazaro.pathguide

data class PathGuideConfig(
    val enabled: Boolean = true,
    val sensitivity: Float = 1f,
    val volume: Float = 0.75f,
    val frontalAlertsEnabled: Boolean = true,
    val stairAlertsEnabled: Boolean = false,
    val doorwayAlertsEnabled: Boolean = true,
    val sceneDescriptionsEnabled: Boolean = true,
    val sceneDescriptionIntervalSec: Int = 30,
)
