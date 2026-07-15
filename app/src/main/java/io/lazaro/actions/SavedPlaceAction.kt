package io.lazaro.actions

import android.content.Context
import android.location.Geocoder
import dagger.hilt.android.qualifiers.ApplicationContext
import io.lazaro.memory.SavedPlaceRepository
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SavedPlaceAction @Inject constructor(
    @ApplicationContext private val context: Context,
    private val savedPlaceRepository: SavedPlaceRepository,
    private val locationAction: LocationAction,
    private val savedPlaceIntentDetector: SavedPlaceIntentDetector,
) {
    suspend fun tryPrepare(userText: String): ActionResult? {
        return when (savedPlaceIntentDetector.detect(userText)) {
            SavedPlaceIntent.SAVE -> prepareSave(userText)
            SavedPlaceIntent.LIST -> listPlaces()
            SavedPlaceIntent.DELETE -> prepareDelete(userText)
            null -> null
        }
    }

    suspend fun confirmSave(args: Map<String, String>): ActionResult {
        val name = args["name"].orEmpty().trim()
        val lat = args["latitude"]?.toDoubleOrNull()
        val lng = args["longitude"]?.toDoubleOrNull()
        val address = args["address"]?.ifBlank { null }
        if (name.isBlank() || lat == null || lng == null) {
            return ActionResult.Error("No tengo los datos del sitio listos.")
        }
        val place = savedPlaceRepository.savePlace(name, lat, lng, address)
        val addressHint = place.address?.let { " Cerca de $it." } ?: ""
        return ActionResult.Success(
            "Sitio ${place.displayName} guardado.$addressHint " +
                "Cuando quieras, di llévame a ${place.displayName}.",
        )
    }

    suspend fun confirmDelete(args: Map<String, String>): ActionResult {
        val name = args["name"].orEmpty()
        val deleted = savedPlaceRepository.deletePlace(name)
        return if (deleted) {
            ActionResult.Success("Sitio $name borrado.")
        } else {
            ActionResult.Error("No encuentro el sitio $name.")
        }
    }

    private suspend fun prepareSave(userText: String): ActionResult {
        val name = savedPlaceIntentDetector.extractPlaceName(userText)
        if (name.isNullOrBlank()) {
            return ActionResult.Success(
                "¿Cómo quieres llamar a este sitio? " +
                    "Di por ejemplo: guarda sitio farmacia, o guarda posición panadería.",
            )
        }

        val location = locationAction.getCurrentLocation()
            ?: return ActionResult.Error(
                "No pude obtener tu ubicación. Activa el GPS y vuelve a intentarlo.",
            )

        val address = reverseGeocode(location.latitude, location.longitude)
        val addressHint = address?.let { " Estás cerca de $it." } ?: ""
        return ActionResult.NeedsConfirmation(
            prompt = "¿Guardo la posición actual como $name?$addressHint Di sí o no.",
            pendingAction = PendingAction(
                toolName = "save_saved_place",
                args = mapOf(
                    "name" to name,
                    "latitude" to location.latitude.toString(),
                    "longitude" to location.longitude.toString(),
                    "address" to (address ?: ""),
                ),
            ),
        )
    }

    private suspend fun listPlaces(): ActionResult {
        val places = savedPlaceRepository.getAllPlaces()
        if (places.isEmpty()) {
            return ActionResult.Success(
                "No tienes sitios guardados. Di guarda sitio y el nombre para marcar donde estás.",
            )
        }
        val lines = places.take(8).joinToString(". ") { place ->
            val addr = place.address?.let { " ($it)" }.orEmpty()
            "${place.displayName}$addr"
        }
        return ActionResult.Success("Tienes ${places.size} sitios guardados: $lines")
    }

    private suspend fun prepareDelete(userText: String): ActionResult {
        val name = savedPlaceIntentDetector.extractPlaceName(userText)
            ?: return ActionResult.Error("¿Qué sitio quieres borrar? Di por ejemplo: borra sitio farmacia.")
        val place = savedPlaceRepository.resolvePlace(name)
            ?: return ActionResult.Error("No encuentro el sitio $name.")
        return ActionResult.NeedsConfirmation(
            prompt = "¿Seguro que quieres borrar el sitio ${place.displayName}? Di sí o no.",
            pendingAction = PendingAction(
                toolName = "delete_saved_place",
                args = mapOf("name" to place.displayName),
            ),
        )
    }

    private suspend fun reverseGeocode(lat: Double, lng: Double): String? {
        if (!Geocoder.isPresent()) return null
        return try {
            @Suppress("DEPRECATION")
            val addresses = Geocoder(context, Locale.getDefault()).getFromLocation(lat, lng, 1)
            addresses?.firstOrNull()?.getAddressLine(0)?.trim()?.ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }
}
