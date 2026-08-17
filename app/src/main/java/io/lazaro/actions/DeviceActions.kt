package io.lazaro.actions

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.qualifiers.ApplicationContext
import io.lazaro.messaging.MessageRepository
import io.lazaro.messaging.NotificationAccessHelper
import io.lazaro.messaging.LazaroNotificationListenerService
import io.lazaro.messaging.entity.MessageApps
import io.lazaro.navigation.MapsLaunchActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NavigationAction @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val packageManager get() = context.packageManager

    /**
     * Navegación peatonal in-app (Directions + PathGuide).
     * Ya no abre la app Google Maps; [launchWalkingNavigation] queda como no-op que indica éxito
     * para el flujo de sesión (compatibilidad con deferrers).
     */
    suspend fun navigateTo(destination: String): ActionResult {
        if (destination.isBlank()) {
            return ActionResult.Error("No he entendido el destino. ¿A dónde quieres ir?")
        }
        return ActionResult.Success(
            "Te guío a pie hasta $destination.",
            suspendListening = true,
        )
    }

    suspend fun navigateToCoordinates(
        latitude: Double,
        longitude: Double,
        label: String,
        distanceMeters: Int = 0,
    ): ActionResult {
        val distanceHint = if (distanceMeters > 0) " Está a unos $distanceMeters metros." else ""
        return ActionResult.Success(
            "Te guío a pie hasta $label.$distanceHint",
            suspendListening = true,
        )
    }

    suspend fun openTransitRoute(
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

    fun openTransitPlan(destination: String): ActionResult = ActionResult.Error(
        "Confirma la ruta en transporte público antes de abrir Maps.",
    )

    /** Compat: no abre Maps; la sesión in-app la arranca el AssistantController. */
    suspend fun launchWalkingNavigation(destination: String): Boolean {
        return destination.isNotBlank()
    }

    suspend fun launchWalkingNavigation(
        destination: String,
        originLat: Double?,
        originLng: Double?,
    ): Boolean {
        return destination.isNotBlank()
    }

    suspend fun launchWalkingNavigationToCoordinates(
        latitude: Double,
        longitude: Double,
        label: String,
    ): Boolean {
        return label.isNotBlank() || (latitude != 0.0 || longitude != 0.0)
    }

    suspend fun launchWalkingNavigationToCoordinates(
        latitude: Double,
        longitude: Double,
        label: String,
        originLat: Double?,
        originLng: Double?,
    ): Boolean {
        return label.isNotBlank() || (latitude != 0.0 || longitude != 0.0)
    }

    suspend fun launchTransitRoute(
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

    private fun buildGeoFallbackIntent(query: String, coordinates: String? = null): Intent {
        val target = coordinates ?: query
        val uri = "geo:0,0?q=${Uri.encode(target)}(${Uri.encode(query)})".toUri()
        return Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private suspend fun launchFirstResolvable(intents: List<Intent>): Boolean {
        val resolvable = intents.filter { canResolve(it) }
        if (resolvable.isEmpty()) {
            Log.w(TAG, "No resolvable Maps intents for ${intents.map { it.data }}")
            return false
        }
        val launched = MapsLaunchActivity.launch(context, resolvable)
        if (!launched) {
            Log.w(TAG, "MapsLaunchActivity could not open any intent")
        }
        return launched
    }

    private fun canResolve(intent: Intent): Boolean {
        return packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null ||
            packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty()
    }

    private fun isMapsInstalled(): Boolean {
        return packageManager.getLaunchIntentForPackage(GOOGLE_MAPS_PACKAGE) != null
    }

    companion object {
        private const val TAG = "NavigationAction"
        private const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"
    }
}

@Singleton
class LocationAction @Inject constructor(
    @ApplicationContext private val context: Context,
    private val nearbyLandmarkRepository: io.lazaro.location.NearbyLandmarkRepository,
) {
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

    data class UserLocation(
        val latitude: Double,
        val longitude: Double,
        val ageMs: Long = 0L,
    )

    /**
     * Ubicación rápida: lastLocation reciente primero; si no, fix fresco con timeout corto.
     */
    suspend fun getCurrentLocation(): UserLocation? {
        return try {
            recentLastLocation()
                ?: freshLocation(FRESH_FIX_TIMEOUT_MS)
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    suspend fun whereAmI(): ActionResult {
        return try {
            val location = getCurrentLocation()
                ?: return ActionResult.Error(
                    "No pude obtener tu ubicación. Comprueba que el GPS esté activado.",
                )

            val addressLine = reverseGeocodeFast(location.latitude, location.longitude)
            val landmarks = nearbyLandmarkRepository.findNearby(
                location.latitude,
                location.longitude,
            )
            val landmarkLine = nearbyLandmarkRepository.formatSpokenLine(landmarks)

            val base = if (addressLine.isNullOrBlank()) {
                "Estás en las coordenadas ${"%.5f".format(location.latitude)}, " +
                    "${"%.5f".format(location.longitude)}."
            } else {
                "Estás cerca de $addressLine."
            }
            val spoken = if (landmarkLine.isNullOrBlank()) base else "$base $landmarkLine"
            ActionResult.Success(spoken)
        } catch (e: SecurityException) {
            ActionResult.Error("Necesito permiso de ubicación para decirte dónde estás.")
        } catch (e: Exception) {
            ActionResult.Error("No pude obtener tu ubicación: ${e.localizedMessage ?: "error desconocido"}.")
        }
    }

    private suspend fun recentLastLocation(): UserLocation? {
        return try {
            @Suppress("DEPRECATION")
            val last = fusedLocationClient.lastLocation.await() ?: return null
            val age = System.currentTimeMillis() - last.time
            if (age < 0 || age > MAX_LAST_LOCATION_AGE_MS) return null
            if (last.latitude == 0.0 && last.longitude == 0.0) return null
            UserLocation(last.latitude, last.longitude, ageMs = age)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun freshLocation(timeoutMs: Long): UserLocation? {
        val cts = CancellationTokenSource()
        return try {
            withTimeout(timeoutMs) {
                val location = fusedLocationClient.getCurrentLocation(
                    Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                    cts.token,
                ).await() ?: return@withTimeout null
                UserLocation(location.latitude, location.longitude, ageMs = 0L)
            }
        } catch (_: TimeoutCancellationException) {
            null
        } catch (_: Exception) {
            null
        } finally {
            try {
                cts.cancel()
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun reverseGeocodeFast(lat: Double, lon: Double): String? {
        if (!Geocoder.isPresent()) return null
        return try {
            withTimeout(GEOCODER_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    Geocoder(context, Locale.getDefault())
                        .getFromLocation(lat, lon, 1)
                        ?.firstOrNull()
                        ?.getAddressLine(0)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /** lastLocation usable hasta 3 min (voz rápida en interior). */
        private const val MAX_LAST_LOCATION_AGE_MS = 180_000L
        private const val FRESH_FIX_TIMEOUT_MS = 4_500L
        private const val GEOCODER_TIMEOUT_MS = 2_500L
    }
}

@Singleton
class MessagesAction @Inject constructor(
    private val messageRepository: MessageRepository,
    private val notificationAccessHelper: NotificationAccessHelper,
    private val replyContext: io.lazaro.messaging.ReplyContext,
    private val contactResolver: io.lazaro.contacts.ContactResolver,
    private val whatsAppVoiceNoteAction: io.lazaro.messaging.WhatsAppVoiceNoteAction,
) {
    suspend fun readMessages(): ActionResult {
        if (!notificationAccessHelper.isNotificationListenerEnabled()) {
            notificationAccessHelper.openNotificationAccessSettings()
            return ActionResult.Error(
                "Necesito acceso a notificaciones para leer WhatsApp. " +
                    "Te abro los ajustes. Activa Lazaro y vuelve a pedírmelo.",
            )
        }
        // Reescanea la barra: mensajes ya presentes no llegan solo por onNotificationPosted.
        LazaroNotificationListenerService.syncActiveMessages()
        val summary = messageRepository.buildSpokenSummary()
        if (summary.startsWith("No tienes")) {
            return ActionResult.Success(summary)
        }
        val sender = replyContext.lastSender ?: return ActionResult.Success(summary)
        val contact = contactResolver.findSingleOrNull(sender)
            ?: contactResolver.findContacts(sender).firstOrNull()
        val name = contact?.displayName ?: sender
        val phone = contact?.phoneNumber.orEmpty()
        val pkg = replyContext.lastSenderPackage.orEmpty()
        // Oferta de respuesta = siempre canal WhatsApp (no reenviar por SMS).
        val waPkg = when (pkg) {
            MessageApps.WHATSAPP_BUSINESS -> MessageApps.WHATSAPP_BUSINESS
            else -> MessageApps.WHATSAPP
        }
        val offer = whatsAppVoiceNoteAction.prepareOfferReply(name, phone, waPkg)
        // Anteponer el resumen leído al prompt de oferta (corto al final).
        return if (offer is ActionResult.NeedsConfirmation) {
            ActionResult.NeedsConfirmation(
                prompt = "$summary ${offer.prompt}",
                pendingAction = offer.pendingAction,
            )
        } else {
            ActionResult.Success(summary)
        }
    }
}
