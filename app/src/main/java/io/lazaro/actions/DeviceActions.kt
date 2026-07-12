package io.lazaro.actions

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.net.Uri
import androidx.core.net.toUri
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.qualifiers.ApplicationContext
import io.lazaro.messaging.MessageRepository
import io.lazaro.messaging.NotificationAccessHelper
import kotlinx.coroutines.tasks.await
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NavigationAction @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val packageManager get() = context.packageManager

    fun navigateTo(destination: String): ActionResult {
        if (destination.isBlank()) {
            return ActionResult.Error("No he entendido el destino. ¿A dónde quieres ir?")
        }

        if (!launchWalkingNavigation(destination)) {
            return ActionResult.Error("No pude abrir la navegación hacia $destination.")
        }

        return ActionResult.Success(
            "Navegación a pie iniciada hacia $destination.",
            suspendListening = true,
        )
    }

    fun navigateToCoordinates(
        latitude: Double,
        longitude: Double,
        label: String,
        distanceMeters: Int = 0,
    ): ActionResult {
        if (!launchWalkingNavigationToCoordinates(latitude, longitude, label)) {
            return ActionResult.Error("No pude abrir la navegación hacia $label.")
        }

        val distanceHint = if (distanceMeters > 0) " Está a unos $distanceMeters metros." else ""
        return ActionResult.Success(
            "Navegación a pie iniciada hacia $label.$distanceHint",
            suspendListening = true,
        )
    }

    fun openTransitRoute(
        destination: String,
        originLat: Double? = null,
        originLng: Double? = null,
    ): ActionResult {
        if (destination.isBlank()) {
            return ActionResult.Error("No he entendido el destino.")
        }

        if (!launchTransitRoute(destination, originLat, originLng)) {
            return ActionResult.Error("No pude abrir la ruta en transporte público.")
        }

        return ActionResult.Success(
            "Ruta en transporte público abierta hacia $destination.",
            suspendListening = true,
        )
    }

    fun openTransitPlan(destination: String): ActionResult = openTransitRoute(destination)

    fun launchWalkingNavigation(destination: String): Boolean {
        if (destination.isBlank()) return false
        return launchFirstResolvable(buildWalkingNavigationIntents(destination))
    }

    fun launchWalkingNavigationToCoordinates(
        latitude: Double,
        longitude: Double,
        label: String,
    ): Boolean {
        return launchFirstResolvable(buildWalkingNavigationIntents("$latitude,$longitude", label))
    }

    fun launchTransitRoute(
        destination: String,
        originLat: Double? = null,
        originLng: Double? = null,
    ): Boolean {
        val uriBuilder = Uri.parse("https://www.google.com/maps/dir/?api=1").buildUpon()
            .appendQueryParameter("destination", destination)
            .appendQueryParameter("travelmode", "transit")
            .appendQueryParameter("dir_action", "navigate")

        if (originLat != null && originLng != null) {
            uriBuilder.appendQueryParameter("origin", "$originLat,$originLng")
        }

        val intent = Intent(Intent.ACTION_VIEW, uriBuilder.build()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (isMapsInstalled()) setPackage(GOOGLE_MAPS_PACKAGE)
        }
        return launchFirstResolvable(listOf(intent, buildGeoFallbackIntent(destination)))
    }

    private fun buildWalkingNavigationIntents(destination: String, label: String? = null): List<Intent> {
        val encodedDestination = Uri.encode(destination)
        val intents = mutableListOf<Intent>()

        if (isMapsInstalled()) {
            intents += Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/maps/dir/?api=1")
                    .buildUpon()
                    .appendQueryParameter("destination", destination)
                    .appendQueryParameter("travelmode", "walking")
                    .appendQueryParameter("dir_action", "navigate")
                    .build(),
            ).apply {
                setPackage(GOOGLE_MAPS_PACKAGE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            intents += Intent(
                Intent.ACTION_VIEW,
                "google.navigation:q=$encodedDestination&mode=w".toUri(),
            ).apply {
                setPackage(GOOGLE_MAPS_PACKAGE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        intents += buildGeoFallbackIntent(label ?: destination, destination)
        return intents
    }

    private fun buildGeoFallbackIntent(query: String, coordinates: String? = null): Intent {
        val target = coordinates ?: query
        val uri = "geo:0,0?q=${Uri.encode(target)}(${Uri.encode(query)})".toUri()
        return Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun launchFirstResolvable(intents: List<Intent>): Boolean {
        for (intent in intents) {
            if (canResolve(intent)) {
                context.startActivity(intent)
                return true
            }
        }
        return false
    }

    private fun canResolve(intent: Intent): Boolean {
        return packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
    }

    private fun isMapsInstalled(): Boolean {
        return packageManager.getLaunchIntentForPackage(GOOGLE_MAPS_PACKAGE) != null
    }

    companion object {
        private const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"
    }
}

@Singleton
class LocationAction @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

    data class UserLocation(val latitude: Double, val longitude: Double)

    suspend fun getCurrentLocation(): UserLocation? {
        return try {
            val location = fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                CancellationTokenSource().token,
            ).await() ?: return null
            UserLocation(location.latitude, location.longitude)
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    suspend fun whereAmI(): ActionResult {
        return try {
            val location = fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                CancellationTokenSource().token,
            ).await()

            if (location == null) {
                return ActionResult.Error(
                    "No pude obtener tu ubicación. Comprueba que el GPS esté activado.",
                )
            }

            if (!Geocoder.isPresent()) {
                return ActionResult.Success(
                    "Estás en las coordenadas ${location.latitude}, ${location.longitude}.",
                )
            }

            @Suppress("DEPRECATION")
            val addresses = Geocoder(context, Locale.getDefault()).getFromLocation(
                location.latitude,
                location.longitude,
                1,
            )

            val addressLine = addresses?.firstOrNull()?.getAddressLine(0)
            if (addressLine.isNullOrBlank()) {
                ActionResult.Success(
                    "Estás en las coordenadas ${location.latitude}, ${location.longitude}.",
                )
            } else {
                ActionResult.Success("Estás cerca de $addressLine.")
            }
        } catch (e: SecurityException) {
            ActionResult.Error("Necesito permiso de ubicación para decirte dónde estás.")
        } catch (e: Exception) {
            ActionResult.Error("No pude obtener tu ubicación: ${e.localizedMessage ?: "error desconocido"}.")
        }
    }
}

@Singleton
class MessagesAction @Inject constructor(
    private val messageRepository: MessageRepository,
    private val notificationAccessHelper: NotificationAccessHelper,
) {
    suspend fun readMessages(): ActionResult {
        if (!notificationAccessHelper.isNotificationListenerEnabled()) {
            notificationAccessHelper.openNotificationAccessSettings()
            return ActionResult.Error(
                "Necesito acceso a notificaciones para leer WhatsApp. " +
                    "Te abro los ajustes. Activa Lazaro y vuelve a pedírmelo.",
            )
        }
        return ActionResult.Success(messageRepository.buildSpokenSummary())
    }
}
