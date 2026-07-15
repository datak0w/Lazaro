package io.lazaro.routes

import io.lazaro.memory.MemoryRepository
import io.lazaro.routes.entity.SavedRoute
import javax.inject.Inject
import javax.inject.Singleton

data class ResolvedRoute(
    val route: SavedRoute,
    val destinationLabel: String,
    val memoryKey: String?,
)

@Singleton
class RouteResolver @Inject constructor(
    private val routeRepository: RouteRepository,
    private val memoryRepository: MemoryRepository,
) {
    suspend fun resolveForDestination(destinationRaw: String): ResolvedRoute? {
        val normalized = destinationRaw.trim().lowercase()
        val memoryValue = memoryRepository.resolveMemoryValue(normalized)
            ?: memoryRepository.resolveMemoryValue(destinationRaw.trim())
        val label = memoryValue ?: destinationRaw.trim()

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

        if (memoryValue != null) {
            val geocoded = geocodeApprox(memoryValue)
            if (geocoded != null) {
                routeRepository.findRouteNearEnd(geocoded.first, geocoded.second)?.let { route ->
                    return ResolvedRoute(route, label, normalized)
                }
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
        return null
    }
}
