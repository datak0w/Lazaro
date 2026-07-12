package io.lazaro.transit

import io.lazaro.actions.ActionResult
import io.lazaro.actions.LocationAction
import io.lazaro.actions.NavigationAction
import io.lazaro.actions.PendingAction
import io.lazaro.memory.MemoryRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransitAction @Inject constructor(
    private val transitIntentDetector: TransitIntentDetector,
    private val overpassTransitRepository: OverpassTransitRepository,
    private val locationAction: LocationAction,
    private val navigationAction: NavigationAction,
    private val memoryRepository: MemoryRepository,
) {
    suspend fun tryPrepare(userText: String): ActionResult? {
        return when (val intent = transitIntentDetector.detectIntent(userText)) {
            is TransitUserIntent.PlanRoute -> prepareTransitRoute(intent.destination)
            is TransitUserIntent.FindNearby -> prepareFindTransit(intent.mode)
            null -> null
        }
    }

    suspend fun prepareTransitRoute(rawDestination: String): ActionResult {
        val destination = memoryRepository.resolveMemoryValue(rawDestination.trim())
            ?: rawDestination.trim()

        if (destination.isBlank()) {
            return ActionResult.Error("No he entendido a dónde quieres ir. Di: ruta en metro a casa.")
        }

        return ActionResult.NeedsConfirmation(
            prompt = "¿Planifico ruta en transporte público hasta $destination? " +
                "Google Maps te dirá líneas, transbordos y horarios. Di sí o no.",
            pendingAction = PendingAction(
                toolName = "plan_transit_route",
                args = mapOf("destination" to destination),
            ),
        )
    }

    suspend fun confirmTransitRoute(args: Map<String, String>): ActionResult {
        val destination = args["destination"].orEmpty()
        if (destination.isBlank()) {
            return ActionResult.Error("No tengo el destino de la ruta.")
        }

        val location = locationAction.getCurrentLocation()
        return navigationAction.openTransitRoute(
            destination = destination,
            originLat = location?.latitude,
            originLng = location?.longitude,
        )
    }

    suspend fun prepareFindTransit(mode: TransitMode): ActionResult {
        val location = locationAction.getCurrentLocation()
            ?: return ActionResult.Error("Necesito tu ubicación para buscar paradas cercanas.")

        val stops = overpassTransitRepository.findNearbyStops(
            latitude = location.latitude,
            longitude = location.longitude,
            mode = mode,
        )

        if (stops.isEmpty()) {
            return ActionResult.Error(
                "No encuentro paradas de ${mode.spokenLabel} cerca. Prueba en otra zona o di metro, bus o tren.",
            )
        }

        if (stops.size == 1) {
            return confirmNavigate(stops.first())
        }

        val options = stops.take(5).mapIndexed { index, stop ->
            val distance = formatDistance(stop.distanceMeters)
            val line = stop.lineInfo?.let { ", línea $it" }.orEmpty()
            "${index + 1}: ${stop.name} ($distance, ${stop.type.spokenLabel}$line)"
        }.joinToString(". ")

        return ActionResult.NeedsConfirmation(
            prompt = "Paradas de ${mode.spokenLabel} cerca: $options. Di el número o el nombre.",
            pendingAction = PendingAction(
                toolName = "select_transit_stop",
                args = stops.take(5).mapIndexed { index, stop ->
                    "candidate_$index" to encodeStop(stop)
                }.toMap() + mapOf("mode" to mode.name),
            ),
        )
    }

    fun resolveSelection(args: Map<String, String>, selection: String): TransitStop? {
        val candidates = args.filterKeys { it.startsWith("candidate_") }
            .values
            .mapNotNull { decodeStop(it) }

        parseNumericSelection(selection)?.let { index ->
            if (index in candidates.indices) return candidates[index]
        }

        val normalized = selection.lowercase().trim()
        return candidates.find {
            it.name.lowercase().contains(normalized) || normalized.contains(it.name.lowercase())
        }
    }

    suspend fun confirmSelection(args: Map<String, String>, selection: String): ActionResult {
        val stop = resolveSelection(args, selection)
            ?: return ActionResult.Error("No he entendido qué parada quieres. Di el número o el nombre.")

        return confirmNavigate(stop)
    }

    suspend fun confirmNavigateToStop(args: Map<String, String>): ActionResult {
        val stop = decodeStopFromArgs(args)
            ?: return ActionResult.Error("No tengo la parada lista.")

        return navigationAction.navigateToCoordinates(
            latitude = stop.latitude,
            longitude = stop.longitude,
            label = stop.name,
            distanceMeters = stop.distanceMeters,
        )
    }

    private fun confirmNavigate(stop: TransitStop): ActionResult {
        val distance = formatDistance(stop.distanceMeters)
        return ActionResult.NeedsConfirmation(
            prompt = "La más cercana es ${stop.name}, ${stop.type.spokenLabel}, a $distance. " +
                "¿Te guío a pie hasta allí con Google Maps? Di sí o no.",
            pendingAction = PendingAction(
                toolName = "navigate_transit_stop",
                args = stopArgs(stop),
            ),
        )
    }

    private fun encodeStop(stop: TransitStop): String {
        return listOf(
            stop.name,
            stop.type.name,
            stop.latitude.toString(),
            stop.longitude.toString(),
            stop.distanceMeters.toString(),
            stop.lineInfo.orEmpty(),
        ).joinToString("||")
    }

    private fun decodeStop(raw: String): TransitStop? {
        val parts = raw.split("||")
        if (parts.size < 5) return null
        return TransitStop(
            name = parts[0],
            type = runCatching { TransitMode.valueOf(parts[1]) }.getOrDefault(TransitMode.ANY),
            latitude = parts[2].toDoubleOrNull() ?: return null,
            longitude = parts[3].toDoubleOrNull() ?: return null,
            distanceMeters = parts[4].toIntOrNull() ?: 0,
            lineInfo = parts.getOrNull(5)?.ifBlank { null },
        )
    }

    private fun decodeStopFromArgs(args: Map<String, String>): TransitStop? {
        return TransitStop(
            name = args["name"].orEmpty(),
            type = runCatching { TransitMode.valueOf(args["type"].orEmpty()) }
                .getOrDefault(TransitMode.ANY),
            latitude = args["lat"]?.toDoubleOrNull() ?: return null,
            longitude = args["lng"]?.toDoubleOrNull() ?: return null,
            distanceMeters = args["distance"]?.toIntOrNull() ?: 0,
            lineInfo = args["line_info"]?.ifBlank { null },
        )
    }

    private fun stopArgs(stop: TransitStop): Map<String, String> {
        return mapOf(
            "name" to stop.name,
            "type" to stop.type.name,
            "lat" to stop.latitude.toString(),
            "lng" to stop.longitude.toString(),
            "distance" to stop.distanceMeters.toString(),
            "line_info" to stop.lineInfo.orEmpty(),
        )
    }

    private fun formatDistance(meters: Int): String {
        return if (meters >= 1000) {
            "${meters / 1000} kilómetros y ${meters % 1000} metros"
        } else {
            "$meters metros"
        }
    }

    private fun parseNumericSelection(text: String): Int? {
        val normalized = text.lowercase().trim()
        val wordMap = mapOf(
            "uno" to 0, "una" to 0, "primero" to 0,
            "dos" to 1, "segundo" to 1,
            "tres" to 2, "cuatro" to 3, "cinco" to 4,
        )
        wordMap[normalized]?.let { return it }
        normalized.toIntOrNull()?.let { num ->
            if (num in 1..5) return num - 1
        }
        return null
    }
}
