package io.lazaro.routes.recording

import io.lazaro.actions.ActionResult
import io.lazaro.pathguide.PathGuideController
import io.lazaro.pathguide.PathGuideMode
import io.lazaro.routes.RouteRepository
import io.lazaro.routes.entity.SavedRoute
import io.lazaro.routes.fusion.CanonicalResult
import io.lazaro.routes.fusion.RouteCanonicalizer
import io.lazaro.routes.fusion.RouteHeatmapBuilder
import io.lazaro.routes.fusion.toEntity
import io.lazaro.routes.fusion.toSamples
import io.lazaro.routes.location.HighAccuracyLocationProvider
import io.lazaro.routes.map.OjenMapBundle
import io.lazaro.routes.model.RouteCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RouteRecorderController @Inject constructor(
    private val pathGuideController: PathGuideController,
    private val routeRepository: RouteRepository,
    private val routeRecordingSampler: RouteRecordingSampler,
    private val highAccuracyLocationProvider: HighAccuracyLocationProvider,
    private val routeCanonicalizer: RouteCanonicalizer,
    private val routeHeatmapBuilder: RouteHeatmapBuilder,
    private val ojenMapBundle: OjenMapBundle,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var activeRouteId: Long? = null
    private var activeRunId: Long? = null
    private var pendingName: String? = null
    private var pendingDestinationKey: String? = null

    fun isRecording(): Boolean = activeRunId != null

    fun currentRunId(): Long? = activeRunId

    suspend fun startRecording(
        name: String,
        destinationKey: String? = null,
    ): ActionResult {
        if (isRecording()) {
            return ActionResult.Error("Ya hay una grabación en curso. Di para de grabar primero.")
        }
        val routes = routeRepository.getAllRoutes()
        if (routes.size >= RouteRepository.MAX_ROUTES) {
            return ActionResult.Error("Has llegado al máximo de ${RouteRepository.MAX_ROUTES} rutas guardadas.")
        }

        ojenMapBundle.ensureLoaded()

        val routeId = routeRepository.insertRoute(
            SavedRoute(
                name = name,
                destinationKey = destinationKey,
            ),
        )
        val runId = routeRepository.startRun(routeId)
        activeRouteId = routeId
        activeRunId = runId
        pendingName = name
        pendingDestinationKey = destinationKey

        routeRecordingSampler.start(runId)
        routeRecordingSampler.bindGps(scope, highAccuracyLocationProvider.fixes())

        val started = pathGuideController.start(PathGuideMode.GRABANDO)
        if (!started) {
            activeRouteId = null
            activeRunId = null
            return ActionResult.Error("No pude activar la cámara para grabar la ruta.")
        }

        return ActionResult.Success(
            "Grabando ruta $name. Camina con calma el trayecto completo. Di para de grabar al llegar.",
        )
    }

    suspend fun stopRecording(): ActionResult {
        val routeId = activeRouteId ?: return ActionResult.Error("No hay ninguna grabación activa.")
        val runId = activeRunId ?: return ActionResult.Error("No hay ninguna grabación activa.")

        routeRecordingSampler.stop()
        val drained = routeRecordingSampler.drainBuffer()
        if (drained.isNotEmpty()) {
            routeRepository.appendObservations(drained)
        }

        val samples = routeRepository.getObservationsForRun(runId).toSamples()
        val canonical = routeCanonicalizer.buildCanonical(samples)
        if (canonical is CanonicalResult.Invalid) {
            routeRepository.deleteRoute(routeId)
            pathGuideController.stop()
            activeRouteId = null
            activeRunId = null
            return ActionResult.Error(canonical.reason)
        }

        val success = canonical as CanonicalResult.Success
        val existingRoute = routeRepository.getRoute(routeId)!!
        val mergedProfile = if (existingRoute.runCount > 0) {
            val oldProfile = RouteCodec.decodeProfile(existingRoute.canonicalProfileJson)
            routeHeatmapBuilder.mergeProfiles(
                existing = oldProfile,
                newProfile = success.profile,
                existingWeight = existingRoute.runCount.toFloat(),
                newWeight = success.qualityScore,
            )
        } else {
            success.profile
        }

        routeRepository.updateRoute(
            existingRoute.copy(
                canonicalPolyline = success.polylineJson,
                canonicalProfileJson = RouteCodec.encodeProfile(mergedProfile),
                startLat = success.startLat,
                startLng = success.startLng,
                endLat = success.endLat,
                endLng = success.endLng,
                totalLengthM = success.totalLengthM,
                qualityScore = success.qualityScore,
                runCount = 1,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        routeRepository.finishRun(runId, routeRecordingSampler.sampleCount(), routeRecordingSampler.averageAccuracy())
        routeRepository.saveSegments(routeId, success.segments.map { it.toEntity(routeId) })

        pendingDestinationKey?.let { key ->
            routeRepository.linkMemory(key, routeId)
        }

        val allRuns = routeRepository.getRunsForRoute(routeId).map { run ->
            routeRepository.getObservationsForRun(run.id).toSamples()
        }
        val heatmap = routeHeatmapBuilder.build(routeId, mergedProfile, allRuns)
        routeRepository.saveHeatmap(routeId, heatmap)
        routeRepository.incrementRunCount(routeRepository.getRoute(routeId)!!, success.qualityScore)

        pathGuideController.stop()
        activeRouteId = null
        activeRunId = null
        pendingName = null
        pendingDestinationKey = null

        val name = existingRoute.name
        return ActionResult.Success(
            "Ruta $name guardada. ${success.totalLengthM.toInt()} metros, " +
                "${samples.size} muestras. Ya puedes decir llévame a $name para usarla.",
        )
    }

    fun onCorridorSample(
        corridor: io.lazaro.pathguide.CorridorState,
        junction: io.lazaro.pathguide.JunctionType,
        safeSide: io.lazaro.pathguide.RoadSide,
        roadSide: io.lazaro.pathguide.RoadSide,
        obstacleLabel: String?,
        lat: Double,
        lng: Double,
    ) {
        if (!isRecording()) return
        val terrain = ojenMapBundle.classifySegment(lat, lng)
        routeRecordingSampler.onCorridorFrame(
            corridor = corridor,
            junction = junction,
            safeSide = safeSide,
            roadSide = roadSide,
            segmentType = ojenMapBundle.segmentTypeKey(terrain),
            obstacleLabel = obstacleLabel,
        )
    }

    fun appendPeriodicFlush() {
        if (!isRecording()) return
        scope.launch {
            val batch = routeRecordingSampler.drainBuffer()
            if (batch.isNotEmpty()) {
                routeRepository.appendObservations(batch)
            }
        }
    }
}
