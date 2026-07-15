package io.lazaro.routes

import io.lazaro.routes.dao.RouteDao
import io.lazaro.routes.entity.HeatmapCell
import io.lazaro.routes.entity.RouteMemoryLink
import io.lazaro.routes.entity.RouteObservation
import io.lazaro.routes.entity.RouteRun
import io.lazaro.routes.entity.RouteSegment
import io.lazaro.routes.entity.SavedRoute
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

@Singleton
class RouteRepository @Inject constructor(
    private val routeDao: RouteDao,
) {
    suspend fun getAllRoutes(): List<SavedRoute> = routeDao.getAllRoutes()

    suspend fun getRoute(id: Long): SavedRoute? = routeDao.getRoute(id)

    suspend fun findRouteByMemoryKey(keyOrAlias: String): SavedRoute? {
        val link = routeDao.getLinkByKey(keyOrAlias.lowercase()) ?: return null
        return routeDao.getRoute(link.routeId)
    }

    suspend fun findRouteNearEnd(lat: Double, lng: Double, radiusM: Double = 150.0): SavedRoute? {
        return routeDao.getAllRoutes().firstOrNull { route ->
            haversineM(route.endLat, route.endLng, lat, lng) <= radiusM
        }
    }

    suspend fun insertRoute(route: SavedRoute): Long = routeDao.insertRoute(route)

    suspend fun updateRoute(route: SavedRoute) = routeDao.updateRoute(route)

    suspend fun deleteRoute(routeId: Long) = routeDao.deleteRouteCascade(routeId)

    suspend fun linkMemory(memoryKeyOrAlias: String, routeId: Long) {
        routeDao.insertMemoryLink(
            RouteMemoryLink(
                memoryKeyOrAlias = memoryKeyOrAlias.lowercase(),
                routeId = routeId,
            ),
        )
    }

    suspend fun startRun(routeId: Long): Long {
        return routeDao.insertRun(
            RouteRun(
                routeId = routeId,
                startedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun finishRun(runId: Long, sampleCount: Int, gpsQuality: Float) {
        val run = routeDao.getRun(runId) ?: return
        routeDao.updateRun(
            run.copy(
                endedAt = System.currentTimeMillis(),
                sampleCount = sampleCount,
                gpsQuality = gpsQuality,
            ),
        )
    }

    suspend fun appendObservations(observations: List<RouteObservation>) {
        if (observations.isNotEmpty()) {
            routeDao.insertObservations(observations)
        }
    }

    suspend fun getObservationsForRun(runId: Long): List<RouteObservation> {
        return routeDao.getObservationsForRun(runId)
    }

    suspend fun getRunsForRoute(routeId: Long): List<RouteRun> {
        return routeDao.getRunsForRoute(routeId)
    }

    suspend fun saveHeatmap(routeId: Long, cells: List<HeatmapCell>) {
        routeDao.deleteHeatmapForRoute(routeId)
        routeDao.upsertHeatmapCells(cells)
    }

    suspend fun getHeatmap(routeId: Long): List<HeatmapCell> {
        return routeDao.getHeatmapForRoute(routeId)
    }

    suspend fun saveSegments(routeId: Long, segments: List<RouteSegment>) {
        routeDao.deleteSegmentsForRoute(routeId)
        routeDao.insertSegments(segments)
    }

    suspend fun getSegments(routeId: Long): List<RouteSegment> {
        return routeDao.getSegmentsForRoute(routeId)
    }

    suspend fun incrementRunCount(route: SavedRoute, qualityScore: Float) {
        routeDao.updateRoute(
            route.copy(
                runCount = route.runCount + 1,
                qualityScore = qualityScore,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    fun haversineM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    companion object {
        const val MAX_ROUTES = 20
        const val MIN_SAMPLES = 30
        const val MAX_GPS_ACCURACY_M = 25f
    }
}
