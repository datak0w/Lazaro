package io.lazaro.pathguide

enum class PerceptionSource {
    MONOCULAR,
    DEPTH,
    FUSED,
}

/**
 * Corredor transitable estimado en la acera (centro + bordes en coords normalizadas 0–1).
 */
data class WalkableCorridor(
    /** Positivo = desplazarse a la derecha; negativo = a la izquierda. */
    val lateralOffsetNorm: Float = 0f,
    val leftEdgeNorm: Float = 0.22f,
    val rightEdgeNorm: Float = 0.78f,
    val corridorWidthNorm: Float = 0.56f,
    val corridorWidthM: Float? = null,
    val frontalDistanceM: Float? = null,
    val safeSide: RoadSide = RoadSide.UNKNOWN,
    val confidence: Float = 0f,
    val source: PerceptionSource = PerceptionSource.MONOCULAR,
)

/** Perfil lateral de profundidad (mm) muestreado en columnas de la imagen. */
data class DepthColumnProfile(
    val columns: Int,
    val depthMm: FloatArray,
    val confidence: FloatArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DepthColumnProfile) return false
        return columns == other.columns &&
            depthMm.contentEquals(other.depthMm) &&
            confidence.contentEquals(other.confidence)
    }

    override fun hashCode(): Int {
        var result = columns
        result = 31 * result + depthMm.contentHashCode()
        result = 31 * result + confidence.contentHashCode()
        return result
    }
}

data class DepthSnapshot(
    val profile: DepthColumnProfile? = null,
    val frontalDistanceM: Float? = null,
    val source: PerceptionSource = PerceptionSource.MONOCULAR,
    val available: Boolean = false,
)
