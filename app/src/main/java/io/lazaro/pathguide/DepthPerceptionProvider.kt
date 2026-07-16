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
 * Estrategias según [DepthHardwareCapabilities]:
 * - **MONOCULAR**: sin extras.
 * - **LDAF_ONLY**: distancia frontal puntual vía CameraX/Camera2.
 * - **ARCORE_DEPTH**: perfil lateral + frontal vía ARCore Depth API.
 */
@Singleton
class DepthPerceptionProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var lastSnapshot = DepthSnapshot()
    private var capabilities: DepthHardwareCapabilities? = null
    private var arcoreSampler: ArcoreDepthSampler? = null
    private var arcoreSnapshot: DepthSnapshot? = null

    fun configure(capabilities: DepthHardwareCapabilities) {
        this.capabilities = capabilities
    }

    fun start() {
        when (capabilities?.mode) {
            DepthGuidanceMode.ARCORE_DEPTH -> {
                // La sesión la abre ArcorePathGuideCamera.
            }
            DepthGuidanceMode.LDAF_ONLY -> Unit
            else -> Unit
        }
    }

    fun stop() {
        arcoreSampler?.stop()
        arcoreSampler = null
        arcoreSnapshot = null
        lastSnapshot = DepthSnapshot()
    }

    internal fun bindArcoreSampler(sampler: ArcoreDepthSampler?) {
        arcoreSampler = sampler
        if (sampler == null) {
            arcoreSnapshot = null
        }
    }

    internal fun publishArcoreSnapshot(snapshot: DepthSnapshot) {
        arcoreSnapshot = snapshot
        lastSnapshot = snapshot
    }

    fun update(image: ImageProxy?): DepthSnapshot {
        val caps = capabilities
        if (caps == null || caps.mode == DepthGuidanceMode.MONOCULAR) {
            lastSnapshot = DepthSnapshot()
            return lastSnapshot
        }

        if (caps.mode == DepthGuidanceMode.ARCORE_DEPTH) {
            val snapshot = arcoreSnapshot ?: lastSnapshot
            lastSnapshot = snapshot
            return snapshot
        }

        val focusM = image?.let { FocusDistanceProbe.readMeters(it) }
        lastSnapshot = DepthSnapshot(
            profile = null,
            frontalDistanceM = focusM,
            source = if (focusM != null) PerceptionSource.FUSED else PerceptionSource.MONOCULAR,
            available = focusM != null,
        )
        return lastSnapshot
    }

    fun latest(): DepthSnapshot = lastSnapshot

    fun activeMode(): DepthGuidanceMode =
        capabilities?.mode ?: DepthGuidanceMode.MONOCULAR

    companion object {
        private const val TAG = "DepthPerception"
    }
}
