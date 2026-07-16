package io.lazaro.pathguide

import android.content.Context
import android.util.Log
import androidx.camera.core.ImageProxy
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Profundidad opcional para guía exterior.
 *
 * - **LDAF/autofocus** (Pixel 9, etc.): distancia frontal por frame vía CameraX.
 * - **ARCore depth**: reservado; requiere cámara compartida y se activará sin bloquear CameraX.
 */
@Singleton
class DepthPerceptionProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var lastSnapshot = DepthSnapshot()
    private var arcoreSampler: ArcoreDepthSampler? = null

    fun start() {
        if (arcoreSampler == null) {
            arcoreSampler = ArcoreDepthSampler(context)
        }
        arcoreSampler?.tryEnable()
    }

    fun stop() {
        arcoreSampler?.stop()
        lastSnapshot = DepthSnapshot()
    }

    fun update(image: ImageProxy?): DepthSnapshot {
        val focusM = image?.let { FocusDistanceProbe.readMeters(it) }
        val profile = arcoreSampler?.sampleProfile()

        val available = focusM != null || profile != null
        val source = when {
            profile != null && focusM != null -> PerceptionSource.FUSED
            profile != null -> PerceptionSource.DEPTH
            focusM != null -> PerceptionSource.FUSED
            else -> PerceptionSource.MONOCULAR
        }

        lastSnapshot = DepthSnapshot(
            profile = profile,
            frontalDistanceM = focusM,
            source = source,
            available = available,
        )
        return lastSnapshot
    }

    fun latest(): DepthSnapshot = lastSnapshot

    companion object {
        private const val TAG = "DepthPerception"
    }
}

/**
 * Muestreador ARCore aislado: si no puede compartir cámara con CameraX, permanece inactivo.
 */
private class ArcoreDepthSampler(
    private val context: Context,
) {
    private var enabled = false
    private var disabledPermanently = false

    fun tryEnable() {
        if (disabledPermanently || enabled) return
        try {
            val availability = com.google.ar.core.ArCoreApk.getInstance().checkAvailability(context)
            if (!availability.isSupported) {
                disabledPermanently = true
                return
            }
            // Sin reanudar sesión aquí: CameraX ya usa la cámara trasera.
            // El perfil lateral ARCore se activará cuando integremos SharedCamera.
            Log.i("DepthPerception", "ARCore disponible; perfil lateral pendiente de cámara compartida")
            disabledPermanently = true
        } catch (e: Exception) {
            Log.w("DepthPerception", "ARCore no usable con CameraX activa", e)
            disabledPermanently = true
        }
    }

    fun sampleProfile(): DepthColumnProfile? = null

    fun stop() {
        enabled = false
    }
}
