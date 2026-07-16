package io.lazaro.pathguide

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detecta capacidades del dispositivo y elige la estrategia de guía por profundidad.
 */
@Singleton
class DepthHardwareDetector @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Volatile
    private var cached: DepthHardwareCapabilities? = null

    fun detect(depthEnhancedEnabled: Boolean): DepthHardwareCapabilities {
        cached?.let { return it }
        val caps = probe(depthEnhancedEnabled)
        cached = caps
        Log.i(TAG, "Depth hardware: ${caps.mode} (${caps.deviceLabel}) — ${caps.reason}")
        return caps
    }

    fun invalidateCache() {
        cached = null
    }

    private fun probe(depthEnhancedEnabled: Boolean): DepthHardwareCapabilities {
        val manufacturer = Build.MANUFACTURER.orEmpty()
        val model = Build.MODEL.orEmpty()
        val deviceLabel = listOf(manufacturer, model)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "Android" }

        if (!depthEnhancedEnabled) {
            return DepthHardwareCapabilities(
                mode = DepthGuidanceMode.MONOCULAR,
                deviceLabel = deviceLabel,
                arCoreSupported = false,
                arCoreDepthSupported = false,
                ldafLikely = false,
                reason = "Guía por profundidad desactivada en ajustes",
            )
        }

        val arCoreSupported = isArCoreSupported()
        val arCoreDepthSupported = arCoreSupported && isArCoreDepthSupported()
        val ldafLikely = isLdafLikely(manufacturer, model)

        val mode = resolveMode(
            manufacturer = manufacturer,
            model = model,
            arCoreDepthSupported = arCoreDepthSupported,
            ldafLikely = ldafLikely,
        )

        val reason = when (mode) {
            DepthGuidanceMode.ARCORE_DEPTH ->
                "ARCore Depth disponible; la cámara la gestiona ARCore (no CameraX)"
            DepthGuidanceMode.LDAF_ONLY ->
                "LDAF/autofocus disponible; perfil lateral monocular"
            DepthGuidanceMode.MONOCULAR ->
                when {
                    isDevMonocularDevice(model) ->
                        "Dispositivo de desarrollo configurado en monocular"
                    !arCoreSupported ->
                        "ARCore no disponible en este dispositivo"
                    !arCoreDepthSupported ->
                        "ARCore sin Depth API; fallback monocular"
                    else -> "Sin profundidad hardware utilizable"
                }
        }

        return DepthHardwareCapabilities(
            mode = mode,
            deviceLabel = deviceLabel,
            arCoreSupported = arCoreSupported,
            arCoreDepthSupported = arCoreDepthSupported,
            ldafLikely = ldafLikely,
            reason = reason,
        )
    }

    private fun isArCoreSupported(): Boolean {
        return try {
            when (ArCoreApk.getInstance().checkAvailability(context)) {
                ArCoreApk.Availability.SUPPORTED_INSTALLED,
                ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
                ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED,
                -> true
                else -> false
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo comprobar ARCore", e)
            false
        }
    }

    private fun isArCoreDepthSupported(): Boolean {
        var session: Session? = null
        return try {
            session = Session(context)
            session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
        } catch (_: UnavailableArcoreNotInstalledException) {
            false
        } catch (_: UnavailableApkTooOldException) {
            false
        } catch (_: UnavailableDeviceNotCompatibleException) {
            false
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo comprobar Depth API", e)
            false
        } finally {
            try {
                session?.close()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private const val TAG = "DepthHardware"

        internal fun resolveMode(
            manufacturer: String,
            model: String,
            arCoreDepthSupported: Boolean,
            ldafLikely: Boolean,
        ): DepthGuidanceMode {
            if (isDevMonocularDevice(model)) {
                return DepthGuidanceMode.MONOCULAR
            }
            if (isArcorePreferredDevice(manufacturer, model) && arCoreDepthSupported) {
                return DepthGuidanceMode.ARCORE_DEPTH
            }
            if (arCoreDepthSupported && isGoogleDevice(manufacturer)) {
                return DepthGuidanceMode.ARCORE_DEPTH
            }
            if (ldafLikely) {
                return DepthGuidanceMode.LDAF_ONLY
            }
            return DepthGuidanceMode.MONOCULAR
        }

        internal fun isDevMonocularDevice(model: String): Boolean {
            val normalized = model.lowercase()
            return normalized.contains("sm-a346") ||
                normalized.contains("a34") ||
                normalized.contains("galaxy a34")
        }

        internal fun isArcorePreferredDevice(manufacturer: String, model: String): Boolean {
            if (!isGoogleDevice(manufacturer)) return false
            val normalized = model.lowercase()
            return normalized.contains("pixel")
        }

        internal fun isGoogleDevice(manufacturer: String): Boolean {
            return manufacturer.equals("google", ignoreCase = true)
        }

        internal fun isLdafLikely(manufacturer: String, model: String): Boolean {
            if (isGoogleDevice(manufacturer) && model.lowercase().contains("pixel")) {
                return true
            }
            return false
        }
    }
}
