package io.lazaro.routes.recording

import io.lazaro.pathguide.CorridorState
import io.lazaro.pathguide.DeviceRotationTracker
import io.lazaro.pathguide.JunctionType
import io.lazaro.routes.entity.RouteObservation
import io.lazaro.routes.location.GpsFix
import io.lazaro.pathguide.RoadSide
import io.lazaro.routes.model.toStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RouteRecordingSampler @Inject constructor(
    private val deviceRotationTracker: DeviceRotationTracker,
) {
    private var runId: Long = 0L
    private var seq = 0
    private var lastCorridorSampleMs = 0L
    private var lastGps: GpsFix? = null
    private var distanceAlongM = 0f
    private val buffer = mutableListOf<RouteObservation>()
    private var gpsJob: Job? = null
    private var accuracySum = 0f
    private var accuracyCount = 0

    val pendingObservations: List<RouteObservation> get() = buffer.toList()

    fun start(runId: Long) {
        this.runId = runId
        seq = 0
        distanceAlongM = 0f
        buffer.clear()
        accuracySum = 0f
        accuracyCount = 0
        lastCorridorSampleMs = 0L
        lastGps = null
    }

    fun bindGps(
        scope: CoroutineScope,
        fixes: kotlinx.coroutines.flow.Flow<GpsFix>,
    ) {
        gpsJob?.cancel()
        gpsJob = fixes.onEach { fix ->
            val prev = lastGps
            if (prev != null) {
                distanceAlongM += haversineM(prev.lat, prev.lng, fix.lat, fix.lng).toFloat()
            }
            lastGps = fix
            accuracySum += fix.accuracyM
            accuracyCount++
        }.launchIn(scope)
    }

    fun stop() {
        gpsJob?.cancel()
        gpsJob = null
    }

    fun onCorridorFrame(
        corridor: CorridorState,
        junction: JunctionType,
        safeSide: RoadSide,
        roadSide: RoadSide,
        segmentType: String,
        obstacleLabel: String?,
        phase: String = "RECORDING",
        now: Long = System.currentTimeMillis(),
    ): RouteObservation? {
        if (runId == 0L) return null
        if (now - lastCorridorSampleMs < CORRIDOR_INTERVAL_MS) return null
        lastCorridorSampleMs = now

        val gps = lastGps ?: return null
        val obs = RouteObservation(
            runId = runId,
            seq = seq++,
            timestampMs = now,
            lat = gps.lat,
            lng = gps.lng,
            accuracyM = gps.accuracyM,
            bearingDeg = gps.bearingDeg,
            yawDeg = deviceRotationTracker.currentYawDeg(),
            leftP = corridor.leftProximity,
            centerP = corridor.centerProximity,
            rightP = corridor.rightProximity,
            safeSide = safeSide.toStorage(),
            roadSide = roadSide.toStorage(),
            junction = junction.toStorage(),
            phase = phase,
            segmentType = segmentType,
            obstacleLabel = obstacleLabel,
            distanceAlongM = distanceAlongM,
        )
        buffer += obs
        return obs
    }

    fun drainBuffer(): List<RouteObservation> {
        val copy = buffer.toList()
        buffer.clear()
        return copy
    }

    fun averageAccuracy(): Float {
        if (accuracyCount == 0) return 99f
        return accuracySum / accuracyCount
    }

    fun sampleCount(): Int = seq

    private fun haversineM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLng / 2) * kotlin.math.sin(dLng / 2)
        return r * 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    }

    companion object {
        private const val CORRIDOR_INTERVAL_MS = 200L
    }
}

private fun JunctionType.toStorage(): String = name
