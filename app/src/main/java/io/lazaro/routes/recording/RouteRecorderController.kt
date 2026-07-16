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
import io.lazaro.routes.map.OjenOdmBundle
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
    private val ojenOdmBundle: OjenOdmBundle,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var activeRouteId: Long? = null
    private var activeRunId: Long? = null
    private var pendingName: String? = null
    private var pendingDestinationKey: String? = null
    private var passiveLearn = false

    fun isRecording(): Boolean = activeRunId != null && !passiveLearn

    fun isCapturingSamples(): Boolean = activeRunId != null

    fun currentRunId(): Long? = activeRunId

    /** Aprendizaje pasivo mientras se sigue una ruta en modo RUTA. */
    suspend fun startPassiveLearn(routeId: Long) {
        if (activeRunId != null) return
        ojenMapBundle.ensureLoaded()
        ojenOdmBundle.ensureLoaded()
        val runId = routeRepository.startRun(routeId)
        activeRouteId = routeId
        activeRunId = runId
        pendingName = routeRepository.getRoute(routeId)?.name
        pendingDestinationKey = null
        passiveLearn = true
        routeRecordingSampler.start(runId)
        routeRecordingSampler.bindGps(scope, highAccuracyLocationProvider.fixes())
    }

    /**
     * Fusiona el run de aprendizaje pasivo en el perfil/heatmap.
     * No detiene la cámara (eso lo hace el coordinador).
     */
    suspend fun finishPassiveLearn(): String? {
        if (!passiveLearn) return null
        val routeId = activeRouteId ?: return null
        val runId = activeRunId ?: return null

        routeRecordingSampler.stop()
        val drained = routeRecordingSampler.drainBuffer()
        if (drained.isNotEmpty()) {
            routeRepository.appendObservations(drained)
        }

        val samples = routeRepository.getObservationsForRun(runId).toSamples()
        if (samples.size < MIN_PASSIVE_SAMPLES) {
            routeRepository.abandonRun(runId)
            clearActive()
            return null
        }

        val canonical = routeCanonicalizer.buildCanonical(samples)
        if (canonical is CanonicalResult.Invalid) {
            routeRepository.abandonRun(runId)
            clearActive()
            return null
        }

        val success = canonical as CanonicalResult.Success
        val existingRoute = routeRepository.getRoute(routeId) ?: run {
            clearActive()
            return null
        }
        val previousRuns = existingRoute.runCount.coerceAtLeast(1)
        val oldProfile = RouteCodec.decodeProfile(existingRoute.canonicalProfileJson)
        val mergedProfile = if (oldProfile.isNotEmpty()) {
            routeHeatmapBuilder.mergeProfiles(
                existing = oldProfile,
                newProfile = success.profile,
                existingWeight = previousRuns.toFloat(),
                newWeight = success.qualityScore * 0.85f,
            )
        } else {
            success.profile
        }

        val newRunCount = previousRuns + 1
        routeRepository.updateRoute(
            existingRoute.copy(
                canonicalProfileJson = RouteCodec.encodeProfile(mergedProfile),
                qualityScore = ((existingRoute.qualityScore + success.qualityScore) / 2f)
                    .coerceIn(0f, 1f),
                runCount = newRunCount,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        routeRepository.finishRun(runId, routeRecordingSampler.sampleCount(), routeRecordingSampler.averageAccuracy())

        val allRuns = routeRepository.getRunsForRoute(routeId).map { run ->
            routeRepository.getObservationsForRun(run.id).toSamples()
        }
        val heatmap = routeHeatmapBuilder.build(routeId, mergedProfile, allRuns)
        routeRepository.saveHeatmap(routeId, heatmap)

        val name = existingRoute.name
        clearActive()
        return "He aprendido más de la ruta $name. Ya van $newRunCount recorridos."
    }

    private fun clearActive() {
        activeRouteId = null
        activeRunId = null
        pendingName = null
        pendingDestinationKey = null
        passiveLearn = false
    }

    suspend fun startRecording(
        name: String,
        destinationKey: String? = null,
        existingRouteId: Long? = null,
    ): ActionResult {
        if (isRecording()) {
            return ActionResult.Error("Ya hay una grabación en curso. Di para de grabar primero.")
        }

        ojenMapBundle.ensureLoaded()
        ojenOdmBundle.ensureLoaded()

        val routeId: Long
        val displayName: String
        if (existingRouteId != null) {
            val existing = routeRepository.getRoute(existingRouteId)
                ?: return ActionResult.Error("No encuentro esa ruta para aprender más.")
            routeId = existing.id
            displayName = existing.name
        } else {
            val routes = routeRepository.getAllRoutes()
            if (routes.size >= RouteRepository.MAX_ROUTES) {
                return ActionResult.Error("Has llegado al máximo de ${RouteRepository.MAX_ROUTES} rutas guardadas.")
            }
            routeId = routeRepository.insertRoute(
                SavedRoute(
                    name = name,
                    destinationKey = destinationKey,
                ),
            )
            displayName = name
        }

        val runId = routeRepository.startRun(routeId)
        activeRouteId = routeId
        activeRunId = runId
        pendingName = displayName
        pendingDestinationKey = destinationKey

        routeRecordingSampler.start(runId)
        routeRecordingSampler.bindGps(scope, highAccuracyLocationProvider.fixes())

        val started = pathGuideController.start(PathGuideMode.GRABANDO)
        if (!started) {
            activeRouteId = null
            activeRunId = null
            return ActionResult.Error("No pude activar la cámara para grabar la ruta.")
        }

        val learnMsg = if (existingRouteId != null) {
            "Regrabando ruta $displayName para afinarla. Camina el mismo trayecto. Di para de grabar al llegar."
        } else {
            "Grabando ruta $displayName. Camina con calma el trayecto completo. Di para de grabar al llegar."
        }
        return ActionResult.Success(learnMsg)
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
            // No borrar la ruta si era un re-run sobre una existente
            val keepRoute = (routeRepository.getRoute(routeId)?.runCount ?: 0) > 0
            if (!keepRoute) {
                routeRepository.deleteRoute(routeId)
            } else {
                routeRepository.abandonRun(runId)
            }
            pathGuideController.stop()
            clearActive()
            return ActionResult.Error(canonical.reason)
        }

        val success = canonical as CanonicalResult.Success
        val existingRoute = routeRepository.getRoute(routeId)!!
        val previousRuns = existingRoute.runCount.coerceAtLeast(0)
        val mergedProfile = if (previousRuns > 0) {
            val oldProfile = RouteCodec.decodeProfile(existingRoute.canonicalProfileJson)
            routeHeatmapBuilder.mergeProfiles(
                existing = oldProfile,
                newProfile = success.profile,
                existingWeight = previousRuns.toFloat().coerceAtLeast(1f),
                newWeight = success.qualityScore,
            )
        } else {
            success.profile
        }

        val newRunCount = previousRuns + 1
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
                runCount = newRunCount,
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

        pathGuideController.stop()
        clearActive()

        val name = existingRoute.name
        val learnHint = if (newRunCount > 1) {
            " He aprendido más de esta ruta ($newRunCount recorridos)."
        } else {
            ""
        }
        return ActionResult.Success(
            "Ruta $name guardada. ${success.totalLengthM.toInt()} metros, " +
                "${samples.size} muestras.$learnHint Ya puedes decir llévame a $name para usarla.",
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
        phase: String = if (passiveLearn) "REPLAY_LEARN" else "RECORDING",
    ) {
        if (!isCapturingSamples()) return
        val odmTag = ojenOdmBundle.segmentTypeKey(lat, lng)
        val segmentType = if (odmTag != null) {
            odmTag
        } else {
            val terrain = ojenMapBundle.classifySegment(lat, lng)
            ojenMapBundle.segmentTypeKey(terrain)
        }
        routeRecordingSampler.onCorridorFrame(
            corridor = corridor,
            junction = junction,
            safeSide = safeSide,
            roadSide = roadSide,
            segmentType = segmentType,
            obstacleLabel = obstacleLabel,
            phase = phase,
        )
    }

    fun appendPeriodicFlush() {
        if (!isCapturingSamples()) return
        scope.launch {
            val batch = routeRecordingSampler.drainBuffer()
            if (batch.isNotEmpty()) {
                routeRepository.appendObservations(batch)
            }
        }
    }

    companion object {
        private const val MIN_PASSIVE_SAMPLES = 20
    }
}
