package io.lazaro.routes.map

/**
 * Datos vectoriales del corredor pueblo→casa (post-ODM/QGIS).
 * Se empaquetan offline en el móvil; no incluyen GeoTIFF completo.
 */
data class CorridorPathPoint(
    val lat: Double,
    val lng: Double,
)

data class CorridorProfilePoint(
    val lat: Double,
    val lng: Double,
    val distanceAlongM: Float,
    val bearingDeg: Float,
    val gradePct: Float,
    val widthM: Float,
    val segmentTag: String,
)

enum class CorridorNodeType {
    FORK,
    GATE,
    ROAD_CROSS,
    DESTINATION,
    OTHER,
}

data class CorridorNode(
    val lat: Double,
    val lng: Double,
    val distanceAlongM: Float,
    val type: CorridorNodeType,
    val label: String,
)

/** Resultado de proyectar GPS sobre el eje del corredor. */
data class CorridorSnapResult(
    val lat: Double,
    val lng: Double,
    val distanceAlongM: Float,
    val lateralOffsetM: Float,
    val bearingDeg: Float,
    val widthM: Float,
    val gradePct: Float,
    val segmentTag: String,
    val odmScore: Float,
    val onCorridor: Boolean,
)

data class CorridorBundle(
    val name: String,
    val path: List<CorridorPathPoint>,
    val profile: List<CorridorProfilePoint>,
    val nodes: List<CorridorNode>,
) {
    val totalLengthM: Float
        get() = profile.lastOrNull()?.distanceAlongM ?: 0f

    val isEmpty: Boolean
        get() = path.size < 2
}
