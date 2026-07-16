package io.lazaro.pathguide

data class PathGuideConfig(
    val enabled: Boolean = true,
    val sensitivity: Float = 1f,
    val volume: Float = 0.75f,
    val frontalAlertsEnabled: Boolean = true,
    val stairAlertsEnabled: Boolean = false,
    /** Usado en paseo interior; en calle (Maps) manda el sistema de acera. */
    val doorwayAlertsEnabled: Boolean = true,
    /** Desactivado: priorizamos pitidos de acera/calzada, no ML de objetos. */
    val sceneDescriptionsEnabled: Boolean = false,
    val sceneDescriptionIntervalSec: Int = 30,
    /** ARCore + LDAF en Pixel y dispositivos compatibles. */
    val depthEnhancedGuidance: Boolean = true,
)
