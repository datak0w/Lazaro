package io.lazaro.actions

import android.content.Context
import android.location.Geocoder
import android.util.Log
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.qualifiers.ApplicationContext
import io.lazaro.BuildConfig
import io.lazaro.messaging.MessageRepository
import io.lazaro.messaging.NotificationAccessHelper
import io.lazaro.navigation.EmbeddedNavigationEngine
import kotlinx.coroutines.tasks.await
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NavigationAction @Inject constructor(
    @ApplicationContext private val context: Context,
    private val embeddedNavigationEngine: EmbeddedNavigationEngine,
) {

    private val apiKey = BuildConfig.GOOGLE_MAPS_API_KEY

    suspend fun navigateTo(destination: String): ActionResult {
        if (destination.isBlank()) {
            return ActionResult.Error("No he entendido el destino. ¿A dónde quieres ir?")
        }
        if (apiKey.isBlank()) {
            return ActionResult.Error(
                "Falta la clave de Google Maps. Añade GOOGLE_MAPS_API_KEY en local.properties y recompila.",
            )
        }

        val started = embeddedNavigationEngine.startWalkingNavigation(
            destination = destination,
            label = destination,
        )
        return if (started) {
            ActionResult.Success(
                "Navegación a pie iniciada hacia $destination. Te avisaré con voz y vibración en cada giro.",
                suspendListening = true,
            )
        } else {
            ActionResult.Error("No pude calcular la ruta hacia $destination. Comprueba tu conexión y permisos de ubicación.")
        }
    }

    suspend fun navigateToCoordinates(
        latitude: Double,
        longitude: Double,
        label: String,
        distanceMeters: Int = 0,
    ): ActionResult {
        if (apiKey.isBlank()) {
            return ActionResult.Error("Falta la clave de Google Maps en local.properties.")
        }

        val dest = "$latitude,$longitude"
        val started = embeddedNavigationEngine.startWalkingNavigation(
            destination = dest,
            label = label,
            originLat = null,
            originLng = null,
        )
        return if (started) {
            val distanceHint = if (distanceMeters > 0) " Está a unos $distanceMeters metros." else ""
            ActionResult.Success(
                "Navegación a pie iniciada hacia $label.$distanceHint",
                suspendListening = true,
            )
        } else {
            ActionResult.Error("No pude abrir la navegación hacia $label.")
        }
    }

    suspend fun openTransitRoute(
        destination: String,
        originLat: Double? = null,
        originLng: Double? = null,
    ): ActionResult {
        if (destination.isBlank()) {
            return ActionResult.Error("No he entendido el destino.")
        }
        if (apiKey.isBlank()) {
            return ActionResult.Error("Falta la clave de Google Maps.")
        }

        // Para transporte público seguimos abriendo Google Maps externo
        // hasta que implementemos transit propio con Directions API
        return ActionResult.Success(
            "Abriendo ruta en transporte público hacia $destination en Google Maps.",
            suspendListening = true,
        )
    }

    fun openTransitPlan(destination: String): ActionResult = ActionResult.Error(
        "Confirma la ruta en transporte público antes de abrir Maps.",
    )

    fun stopNavigation(): ActionResult {
        embeddedNavigationEngine.stopNavigation()
        return ActionResult.Success("Navegación detenida.")
    }

    companion object {
        private const val TAG = "NavigationAction"
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
                Priority.PRIORITY_HIGH_ACCURACY,
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
                Priority.PRIORITY_HIGH_ACCURACY,
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
