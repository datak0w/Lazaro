package io.lazaro.routes

import android.content.Context
import android.location.Geocoder
import dagger.hilt.android.qualifiers.ApplicationContext
import io.lazaro.memory.MemoryRepository
import io.lazaro.memory.SavedPlaceRepository
import io.lazaro.routes.entity.SavedRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class ResolvedRoute(
    val route: SavedRoute,
    val destinationLabel: String,
    val memoryKey: String?,
)

@Singleton
class RouteResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val routeRepository: RouteRepository,
    private val memoryRepository: MemoryRepository,
    private val savedPlaceRepository: SavedPlaceRepository,
) {
    suspend fun resolveForDestination(destinationRaw: String): ResolvedRoute? {
        val normalized = destinationRaw.trim().lowercase()
        val memoryValue = memoryRepository.resolveMemoryValue(normalized)
            ?: memoryRepository.resolveMemoryValue(destinationRaw.trim())
        val label = memoryValue ?: destinationRaw.trim()

        // 1) Enlace explícito memoria → ruta
        routeRepository.findRouteByMemoryKey(normalized)?.let { route ->
            return ResolvedRoute(route, label, normalized)
        }

        memoryValue?.let { _ ->
            val aliases = listOf("casa", "home_address", normalized)
            for (alias in aliases) {
                routeRepository.findRouteByMemoryKey(alias)?.let { route ->
                    return ResolvedRoute(route, label, alias)
                }
            }
        }

        // 2) Por nombre / etiqueta de destino
        routeRepository.findRouteByNameOrLabel(destinationRaw.trim())?.let { route ->
            return ResolvedRoute(route, label, route.destinationKey)
        }

        // 3) Sitio guardado cerca del extremo de una ruta
        savedPlaceRepository.resolvePlace(destinationRaw)?.let { place ->
            routeRepository.findRouteNearEnd(place.latitude, place.longitude)?.let { route ->
                return ResolvedRoute(route, place.displayName, normalized)
            }
        }

        // 4) Geocodificar destino y buscar ruta cuyo extremo coincida
        val geocoded = geocodeApprox(memoryValue ?: destinationRaw.trim())
        if (geocoded != null) {
            routeRepository.findRouteNearEnd(geocoded.first, geocoded.second)?.let { route ->
                return ResolvedRoute(route, label, normalized)
            }
        }

        return null
    }

    suspend fun findByName(name: String): SavedRoute? {
        val n = name.trim().lowercase()
        return routeRepository.getAllRoutes().firstOrNull {
            it.name.lowercase() == n ||
                it.destinationKey?.lowercase() == n ||
                it.destinationLabel?.lowercase() == n
        }
    }

    private suspend fun geocodeApprox(address: String): Pair<Double, Double>? {
        if (address.isBlank() || !Geocoder.isPresent()) return null
        return withContext(Dispatchers.IO) {
            try {
                @Suppress("DEPRECATION")
                val results = Geocoder(context, Locale("es", "ES")).getFromLocationName(address, 1)
                val first = results?.firstOrNull() ?: return@withContext null
                first.latitude to first.longitude
            } catch (_: Exception) {
                null
            }
        }
    }
}
