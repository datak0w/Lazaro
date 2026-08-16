package io.lazaro.navigation

import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GpsLocationProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)

    /** Snapshot de ubicación actual (una sola vez). */
    suspend fun getCurrentLocation(): Location? {
        return try {
            fusedClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                CancellationTokenSource().token,
            ).await()
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Flujo continuo de ubicaciones con máxima precisión.
     * Fusión de GPS + WiFi + celdas + sensores.
     */
    fun locationUpdates(): Flow<Location> = callbackFlow {
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            2_000L,
        ).apply {
            setWaitForAccurateLocation(true)
            setMinUpdateDistanceMeters(1f)
            setMinUpdateIntervalMillis(1_000L)
        }.build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { trySend(it) }
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                if (!availability.isLocationAvailable) {
                    // No cerramos el canal; el consumidor decide qué hacer con la falta de señal
                }
            }
        }

        try {
            fusedClient.requestLocationUpdates(
                request,
                callback,
                Looper.getMainLooper(),
            ).await()
        } catch (_: SecurityException) {
            close(SecurityException("Permiso de ubicación denegado"))
        } catch (e: Exception) {
            close(e)
        }

        awaitClose {
            fusedClient.removeLocationUpdates(callback)
        }
    }

    /** Última ubicación conocida del cache del sistema. */
    suspend fun getLastLocation(): Location? {
        return try {
            fusedClient.lastLocation.await()
        } catch (_: Exception) {
            null
        }
    }
}
