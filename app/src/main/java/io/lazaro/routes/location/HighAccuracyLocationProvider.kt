package io.lazaro.routes.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

data class GpsFix(
    val lat: Double,
    val lng: Double,
    val accuracyM: Float,
    val bearingDeg: Float,
    val timestampMs: Long,
    /** m/s; negativo si el proveedor no informa velocidad. */
    val speedMps: Float = -1f,
)

@Singleton
class HighAccuracyLocationProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    @Volatile
    private var cached: GpsFix? = null

    /** Último fix con accuracy aceptable (para guía / grabación). */
    @Volatile
    private var lastGood: GpsFix? = null

    @SuppressLint("MissingPermission")
    fun fixes(intervalMs: Long = 1_000L): Flow<GpsFix> = callbackFlow {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .setMaxUpdateDelayMillis(intervalMs * 2)
            .setWaitForAccurateLocation(true)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                val fix = loc.toFix()
                cached = fix
                if (isAcceptable(fix)) {
                    lastGood = fix
                    trySend(fix)
                } else {
                    // No corromper guía con saltos malos; reemitir último bueno si existe
                    lastGood?.let { trySend(it) }
                }
            }
        }

        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        awaitClose { client.removeLocationUpdates(callback) }
    }

    fun lastFixCached(): GpsFix? = lastGood ?: cached

    fun lastGoodFix(): GpsFix? = lastGood

    @SuppressLint("MissingPermission")
    suspend fun lastFix(): GpsFix? {
        return try {
            val loc = com.google.android.gms.tasks.Tasks.await(client.lastLocation)
            val fix = loc?.toFix()
            if (fix != null) {
                cached = fix
                if (isAcceptable(fix)) lastGood = fix
            }
            lastGood ?: fix
        } catch (_: Exception) {
            lastGood ?: cached
        }
    }

    private fun isAcceptable(fix: GpsFix): Boolean {
        if (fix.accuracyM > MAX_ACCURACY_M) return false
        val prev = lastGood ?: return true
        val dt = (fix.timestampMs - prev.timestampMs).coerceAtLeast(1L) / 1000.0
        if (dt <= 0) return true
        val dist = FloatArray(1)
        Location.distanceBetween(prev.lat, prev.lng, fix.lat, fix.lng, dist)
        val jumpM = dist[0]
        // Salto imposible a pie (> ~8 m/s ≈ 29 km/h) con accuracy mala
        val maxJump = (MAX_WALK_SPEED_MPS * dt + fix.accuracyM).toFloat()
        if (jumpM > maxJump && fix.accuracyM > 15f) return false
        if (jumpM > abs(maxJump * 2f) && dt < 3.0) return false
        return true
    }

    private fun Location.toFix(): GpsFix = GpsFix(
        lat = latitude,
        lng = longitude,
        accuracyM = accuracy,
        bearingDeg = bearing,
        timestampMs = time,
        speedMps = if (hasSpeed()) speed else -1f,
    )

    companion object {
        private const val MAX_ACCURACY_M = 25f
        private const val MAX_WALK_SPEED_MPS = 8.0
    }
}
