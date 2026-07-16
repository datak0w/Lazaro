package io.lazaro.memory

import android.content.Context
import android.location.Geocoder
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocationTracker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val memoryRepository: MemoryRepository,
) {
    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)

    suspend fun captureCurrentLocation(source: String = "periodic"): Boolean {
        return try {
            val location = fusedClient.getCurrentLocation(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                CancellationTokenSource().token,
            ).await() ?: return false

            val address = reverseGeocode(location.latitude, location.longitude)
            memoryRepository.recordLocation(
                latitude = location.latitude,
                longitude = location.longitude,
                address = address,
                source = source,
            )
            true
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    /** Descripción corta para el prompt de la IA (no escribe en trail). */
    suspend fun describeCurrentLocationBrief(): String {
        return try {
            val location = fusedClient.getCurrentLocation(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                CancellationTokenSource().token,
            ).await()
            if (location != null) {
                reverseGeocode(location.latitude, location.longitude)
                    ?: "coordenadas ${"%.5f".format(location.latitude)}, ${"%.5f".format(location.longitude)}"
            } else {
                val recent = memoryRepository.getRecentLocations(1).firstOrNull()
                recent?.address
                    ?: recent?.label
                    ?: "desconocida (sin fix GPS)"
            }
        } catch (_: SecurityException) {
            "sin permiso de ubicación"
        } catch (_: Exception) {
            "no disponible ahora"
        }
    }

    suspend fun describeTrailForLostUser(hours: Int = 6): String {
        val trail = memoryRepository.getLocationTrail(hours)
        if (trail.isEmpty()) {
            return "No tengo registros de ubicación recientes."
        }
        val descriptions = trail.take(8).mapIndexed { index, record ->
            val place = record.label ?: record.address ?: "${record.latitude}, ${record.longitude}"
            "${index + 1}. $place"
        }
        return "En las últimas $hours horas has estado en: ${descriptions.joinToString(". ")}."
    }

    private fun reverseGeocode(lat: Double, lng: Double): String? {
        if (!Geocoder.isPresent()) return null
        return try {
            @Suppress("DEPRECATION")
            Geocoder(context, Locale.getDefault())
                .getFromLocation(lat, lng, 1)
                ?.firstOrNull()
                ?.getAddressLine(0)
        } catch (_: Exception) {
            null
        }
    }
}
