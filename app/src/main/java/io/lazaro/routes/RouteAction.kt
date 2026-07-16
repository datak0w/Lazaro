package io.lazaro.routes

import io.lazaro.actions.ActionResult
import io.lazaro.actions.PendingAction
import io.lazaro.routes.recording.RouteRecorderController
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RouteAction @Inject constructor(
    private val routeIntentDetector: RouteIntentDetector,
    private val routeRecorderController: RouteRecorderController,
    private val routeRepository: RouteRepository,
    private val routeResolver: RouteResolver,
    private val hybridNavigationCoordinator: HybridNavigationCoordinator,
) {
    suspend fun tryPrepare(userText: String): ActionResult? {
        val intent = routeIntentDetector.detect(userText) ?: return null
        return when (intent) {
            RouteIntent.START_RECORDING -> {
                val name = routeIntentDetector.extractRouteName(userText)
                    ?: routeIntentDetector.extractDestinationKey(userText)?.let { "ruta a $it" }
                    ?: "ruta ${System.currentTimeMillis() % 10_000}"
                val destKey = routeIntentDetector.extractDestinationKey(userText)
                val existingId = if (routeIntentDetector.isReRecord(userText)) {
                    routeResolver.findByName(name)?.id
                        ?: destKey?.let { routeRepository.findRouteByMemoryKey(it)?.id }
                } else {
                    null
                }
                routeRecorderController.startRecording(name, destKey, existingId)
            }
            RouteIntent.STOP_RECORDING -> routeRecorderController.stopRecording()
            RouteIntent.LIST_ROUTES -> listRoutes()
            RouteIntent.DELETE_ROUTE -> prepareDelete(userText)
            RouteIntent.ROUTE_DETAILS -> routeDetails(userText)
        }
    }

    suspend fun tryPrepareHybridNavigation(destination: String): ActionResult? {
        val resolved = routeResolver.resolveForDestination(destination) ?: return null
        val route = resolved.route
        val runs = route.runCount
        val runsText = if (runs > 1) ", con $runs recorridos" else ""
        val prompt = "Ya tenemos la ruta a ${resolved.destinationLabel} guardada$runsText. " +
            "¿La usamos con guía Lazaro y Maps?"
        return ActionResult.NeedsConfirmation(
            prompt = prompt,
            pendingAction = PendingAction(
                toolName = "follow_saved_route",
                args = mapOf(
                    "route_id" to route.id.toString(),
                    "destination" to resolved.destinationLabel,
                ),
            ),
        )
    }

    suspend fun confirmFollowSavedRoute(args: Map<String, String>): ActionResult {
        val routeId = args["route_id"]?.toLongOrNull()
            ?: return ActionResult.Error("No tengo la ruta lista.")
        val destination = args["destination"].orEmpty()
        val route = routeRepository.getRoute(routeId)
            ?: return ActionResult.Error("La ruta ya no existe.")
        val started = hybridNavigationCoordinator.start(route, destination)
        return if (started) {
            ActionResult.Success(
                "Perfecto. Uso la ruta guardada ${route.name} con Maps y guía Lazaro. " +
                    "Cada paseo afina heatmaps y obstáculos para la próxima vez.",
                suspendListening = true,
            )
        } else {
            ActionResult.Error("No pude iniciar la ruta guardada. Comprueba permisos de cámara y ubicación.")
        }
    }

    private suspend fun listRoutes(): ActionResult {
        val routes = routeRepository.getAllRoutes()
        if (routes.isEmpty()) {
            return ActionResult.Success("No tienes rutas guardadas. Di graba ruta a casa para crear una.")
        }
        val lines = routes.take(8).joinToString(". ") { route ->
            "${route.name}: ${route.totalLengthM.toInt()} metros, ${route.runCount} recorridos"
        }
        return ActionResult.Success("Tienes ${routes.size} rutas. $lines")
    }

    private suspend fun routeDetails(userText: String): ActionResult {
        val name = routeIntentDetector.extractRouteName(userText) ?: "casa"
        val route = routeResolver.findByName(name)
            ?: return ActionResult.Error("No encuentro la ruta $name.")
        val segments = routeRepository.getSegments(route.id)
        val segText = if (segments.isEmpty()) {
            "sin tramos etiquetados"
        } else {
            segments.joinToString(", ") { it.name }
        }
        return ActionResult.Success(
            "Ruta ${route.name}: ${route.totalLengthM.toInt()} metros, ${route.runCount} grabaciones, " +
                "calidad ${(route.qualityScore * 100).toInt()} por ciento. Tramos: $segText.",
        )
    }

    private suspend fun prepareDelete(userText: String): ActionResult {
        val name = routeIntentDetector.extractRouteName(userText) ?: "casa"
        val route = routeResolver.findByName(name)
            ?: return ActionResult.Error("No encuentro la ruta $name.")
        return ActionResult.NeedsConfirmation(
            prompt = "¿Seguro que quieres borrar la ruta ${route.name}? Di sí o no.",
            pendingAction = PendingAction(
                toolName = "delete_saved_route",
                args = mapOf("route_id" to route.id.toString()),
            ),
        )
    }

    suspend fun confirmDeleteRoute(args: Map<String, String>): ActionResult {
        val routeId = args["route_id"]?.toLongOrNull()
            ?: return ActionResult.Error("No tengo la ruta.")
        val route = routeRepository.getRoute(routeId)
        routeRepository.deleteRoute(routeId)
        return ActionResult.Success("Ruta ${route?.name ?: ""} borrada.")
    }
}
