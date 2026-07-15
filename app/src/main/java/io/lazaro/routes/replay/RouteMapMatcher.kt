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

@Singleton
class RouteMapMatcher @Inject constructor(
    private val routeRepository: RouteRepository,
) {
    private var polyline: List<Pair<Double, Double>> = emptyList()
    private var profile: List<CanonicalProfilePoint> = emptyList()
    private var heatmap: List<HeatmapCell> = emptyList()
    private var routeId: Long = 0L
    private var polylineIndex = 0
    private var lastConfidence = 0f

    suspend fun loadRoute(routeId: Long) {
        val route = routeRepository.getRoute(routeId) ?: return
        this.routeId = routeId
        polyline = RouteCodec.decodePolyline(route.canonicalPolyline)
        profile = RouteCodec.decodeProfile(route.canonicalProfileJson)
        heatmap = routeRepository.getHeatmap(routeId)
        polylineIndex = 0
        lastConfidence = 0f
    }

    fun reset() {
        routeId = 0L
        polyline = emptyList()
        profile = emptyList()
        heatmap = emptyList()
        polylineIndex = 0
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
                distanceAlongM = 0f,
                lateralOffsetM = 0f,
                confidence = 0f,
                inReplaySegment = false,
                expectedPoint = null,
            )
        }

        val nearestIdx = findNearestPolylineIndex(lat, lng)
        polylineIndex = nearestIdx
        val expected = profile.getOrNull(nearestIdx) ?: profile.lastOrNull()
        val distAlong = expected?.distanceAlongM ?: 0f

        val lateralOffset = if (expected != null) {
            haversineM(lat, lng, expected.lat, expected.lng).toFloat()
        } else {
            99f
        }

        val corridorScore = if (expected != null) {
            val dl = abs(leftP - expected.leftP)
            val dc = abs(centerP - expected.centerP)
            val dr = abs(rightP - expected.rightP)
            1f - ((dl + dc + dr) / 3f).coerceIn(0f, 1f)
        } else {
            0f
        }

        val gpsScore = (1f - (accuracyM / 30f)).coerceIn(0f, 1f)
        val heatConfidence = heatmap.getOrNull(nearestIdx)?.confidence ?: 0.5f
        val confidence = (corridorScore * 0.5f + gpsScore * 0.2f + heatConfidence * 0.3f)
            .coerceIn(0f, 1f)
        lastConfidence = confidence

        val inReplay = confidence >= REPLAY_THRESHOLD && lateralOffset < MAX_LATERAL_M

        return RouteMatchState(
            routeId = routeId,
            polylineIndex = nearestIdx,
            distanceAlongM = distAlong,
            lateralOffsetM = lateralOffset,
            confidence = confidence,
            inReplaySegment = inReplay,
            expectedPoint = expected,
        )
    }

    fun lastConfidence(): Float = lastConfidence

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
        return best.coerceIn(0, (profile.size - 1).coerceAtLeast(0))
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
    }
}
