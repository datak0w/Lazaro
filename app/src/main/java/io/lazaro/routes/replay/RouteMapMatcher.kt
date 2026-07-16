package io.lazaro.routes.replay

import io.lazaro.routes.RouteRepository
import io.lazaro.routes.entity.HeatmapCell
import io.lazaro.routes.model.CanonicalProfilePoint
import io.lazaro.routes.model.RouteCodec
import io.lazaro.routes.model.RouteMatchState
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Empareja GPS+corredor con el perfil canónico por **distancia-along** (celdas ~2 m),
 * no por índice de polyline cruda.
 */
@Singleton
class RouteMapMatcher @Inject constructor(
    private val routeRepository: RouteRepository,
) {
    private var polyline: List<Pair<Double, Double>> = emptyList()
    private var profile: List<CanonicalProfilePoint> = emptyList()
    private var heatmapByGrid: Map<Int, HeatmapCell> = emptyMap()
    private var heatmapByAlong: List<HeatmapCell> = emptyList()
    private var routeId: Long = 0L
    private var profileIndex = 0
    private var lastConfidence = 0f

    suspend fun loadRoute(routeId: Long) {
        val route = routeRepository.getRoute(routeId) ?: return
        this.routeId = routeId
        polyline = RouteCodec.decodePolyline(route.canonicalPolyline)
        profile = RouteCodec.decodeProfile(route.canonicalProfileJson)
        val heat = routeRepository.getHeatmap(routeId)
        heatmapByAlong = heat.sortedBy { it.distanceAlongM }
        heatmapByGrid = heat.associateBy { it.gridIndex }
        profileIndex = 0
        lastConfidence = 0f
    }

    fun reset() {
        routeId = 0L
        polyline = emptyList()
        profile = emptyList()
        heatmapByGrid = emptyMap()
        heatmapByAlong = emptyList()
        profileIndex = 0
        lastConfidence = 0f
    }

    fun isLoaded(): Boolean = routeId != 0L && profile.isNotEmpty()

    fun update(
        lat: Double,
        lng: Double,
        leftP: Float,
        centerP: Float,
        rightP: Float,
        accuracyM: Float,
    ): RouteMatchState {
        if (!isLoaded()) {
            return RouteMatchState(
                routeId = 0L,
                polylineIndex = 0,
                profileIndex = 0,
                distanceAlongM = 0f,
                lateralOffsetM = 0f,
                confidence = 0f,
                inReplaySegment = false,
                expectedPoint = null,
            )
        }

        // Perfil canónico (pasos ~2 m) como referencia principal
        val nearestProfile = findNearestProfileIndex(lat, lng)
        profileIndex = nearestProfile
        val expected = profile[nearestProfile]
        val distAlong = expected.distanceAlongM

        val lateralOffset = haversineM(lat, lng, expected.lat, expected.lng).toFloat()

        val corridorScore = run {
            val dl = abs(leftP - expected.leftP)
            val dc = abs(centerP - expected.centerP)
            val dr = abs(rightP - expected.rightP)
            1f - ((dl + dc + dr) / 3f).coerceIn(0f, 1f)
        }

        val heat = heatmapFor(nearestProfile, distAlong)
        val gpsScore = (1f - (accuracyM / 30f)).coerceIn(0f, 1f)
        val heatConfidence = heat?.confidence ?: 0.5f
        val confidence = (corridorScore * 0.45f + gpsScore * 0.2f + heatConfidence * 0.35f)
            .coerceIn(0f, 1f)
        lastConfidence = confidence

        val polylineIdx = findNearestPolylineIndex(lat, lng)
        val inReplay = confidence >= REPLAY_THRESHOLD && lateralOffset < MAX_LATERAL_M

        return RouteMatchState(
            routeId = routeId,
            polylineIndex = polylineIdx,
            profileIndex = nearestProfile,
            distanceAlongM = distAlong,
            lateralOffsetM = lateralOffset,
            confidence = confidence,
            inReplaySegment = inReplay,
            expectedPoint = expected,
            heatmapObstacleHits = heat?.obstacleHits ?: 0,
            heatmapVariance = heat?.lateralVariance ?: 0f,
            heatmapSafeSide = heat?.safeSide ?: expected.safeSide,
            heatmapMeanLeft = heat?.meanLeft ?: expected.leftP,
            heatmapMeanRight = heat?.meanRight ?: expected.rightP,
            heatmapConfidence = heatConfidence,
        )
    }

    fun peekAheadObstacle(fromAlongM: Float, aheadM: Float = LOOKAHEAD_M): HeatmapCell? {
        val end = fromAlongM + aheadM
        return heatmapByAlong.firstOrNull { cell ->
            cell.distanceAlongM in fromAlongM..end && cell.obstacleHits >= 1
        }
    }

    fun lastConfidence(): Float = lastConfidence

    private fun heatmapFor(gridIndex: Int, alongM: Float): HeatmapCell? {
        heatmapByGrid[gridIndex]?.let { return it }
        if (heatmapByAlong.isEmpty()) return null
        return heatmapByAlong.minByOrNull { abs(it.distanceAlongM - alongM) }
    }

    private fun findNearestProfileIndex(lat: Double, lng: Double): Int {
        if (profile.isEmpty()) return 0
        var best = 0
        var bestDist = Double.MAX_VALUE
        for (i in profile.indices) {
            val p = profile[i]
            val d = haversineM(lat, lng, p.lat, p.lng)
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        return best
    }

    private fun findNearestPolylineIndex(lat: Double, lng: Double): Int {
        if (polyline.isEmpty()) return 0
        var best = 0
        var bestDist = Double.MAX_VALUE
        for (i in polyline.indices) {
            val (pLat, pLng) = polyline[i]
            val d = haversineM(lat, lng, pLat, pLng)
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        return best
    }

    private fun haversineM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    companion object {
        const val REPLAY_THRESHOLD = 0.55f
        const val MAX_LATERAL_M = 15f
        const val DRIFT_WARN_M = 1.2f
        const val LOOKAHEAD_M = 25f
    }
}
