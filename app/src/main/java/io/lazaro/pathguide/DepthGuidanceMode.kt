package io.lazaro.pathguide

/**
 * Estrategia de percepción espacial elegida según hardware del dispositivo.
 */
enum class DepthGuidanceMode {
    /** Solo cámara RGB + heurísticas (Samsung A34, dispositivos sin extras). */
    MONOCULAR,

    /** CameraX + distancia LDAF/autofocus puntual (Pixel sin sesión ARCore activa). */
    LDAF_ONLY,

    /** ARCore posee la cámara y aporta mapa de profundidad lateral + frontal. */
    ARCORE_DEPTH,
}

data class DepthHardwareCapabilities(
    val mode: DepthGuidanceMode,
    val deviceLabel: String,
    val arCoreSupported: Boolean,
    val arCoreDepthSupported: Boolean,
    val ldafLikely: Boolean,
    val reason: String,
) {
    val usesArcoreCamera: Boolean
        get() = mode == DepthGuidanceMode.ARCORE_DEPTH

    val usesLdaf: Boolean
        get() = mode == DepthGuidanceMode.LDAF_ONLY ||
            mode == DepthGuidanceMode.ARCORE_DEPTH
}
