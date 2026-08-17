package io.lazaro.pathguide

import android.util.Log
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Elige y gestiona la fuente de cámara para PathGuide según hardware detectado.
 * Si ARCore «arranca» pero no entrega frames, cae a CameraX (evita debug negro / nav muda).
 */
@Singleton
class PathGuideCameraHost @Inject constructor(
    private val rearCameraAnalyzer: RearCameraAnalyzer,
    private val arcorePathGuideCamera: ArcorePathGuideCamera,
    private val depthHardwareDetector: DepthHardwareDetector,
) {
    private var activeCapabilities: DepthHardwareCapabilities? = null
    private var frameListener: ((ByteArray, Int, Int, ImageProxy?) -> Unit)? = null

    @Volatile
    private var lastFrameAtMs: Long = 0L

    fun setFrameListener(listener: (ByteArray, Int, Int, ImageProxy?) -> Unit) {
        frameListener = listener
        rearCameraAnalyzer.setFrameListener { gray, width, height, image ->
            lastFrameAtMs = System.currentTimeMillis()
            listener(gray, width, height, image)
        }
        arcorePathGuideCamera.setFrameListener { gray, width, height ->
            lastFrameAtMs = System.currentTimeMillis()
            listener(gray, width, height, null)
        }
    }

    suspend fun start(depthEnhancedEnabled: Boolean): Boolean {
        val capabilities = depthHardwareDetector.detect(depthEnhancedEnabled)
        activeCapabilities = capabilities
        lastFrameAtMs = 0L
        return when (capabilities.mode) {
            DepthGuidanceMode.ARCORE_DEPTH -> {
                val arOk = try {
                    arcorePathGuideCamera.start()
                } catch (e: Exception) {
                    Log.e(TAG, "ARCore start lanzó excepción; fallback CameraX", e)
                    false
                }
                if (!arOk) {
                    return fallbackToCameraX(capabilities, "ARCore falló al abrir")
                }
                // Esperar frames reales; ARCore a veces deja la sesión viva sin imagen.
                val gotFrames = waitForFrames(ARCORE_FRAME_WAIT_MS)
                if (gotFrames) {
                    true
                } else {
                    Log.w(TAG, "ARCore sin frames; fallback CameraX")
                    arcorePathGuideCamera.stop()
                    fallbackToCameraX(capabilities, "ARCore sin frames de cámara")
                }
            }
            DepthGuidanceMode.MONOCULAR,
            DepthGuidanceMode.LDAF_ONLY,
            -> {
                val ok = rearCameraAnalyzer.start()
                if (ok) {
                    // Pequeña espera para confirmar que CameraX entrega frames.
                    if (!waitForFrames(CAMERAX_FRAME_WAIT_MS)) {
                        Log.w(TAG, "CameraX arrancó pero aún no hay frames (permiso/FGS?)")
                    }
                }
                ok
            }
        }
    }

    fun stop() {
        rearCameraAnalyzer.stop()
        arcorePathGuideCamera.stop()
        activeCapabilities = null
        lastFrameAtMs = 0L
    }

    fun isRunning(): Boolean {
        return when (activeCapabilities?.mode) {
            DepthGuidanceMode.ARCORE_DEPTH -> arcorePathGuideCamera.isRunning()
            DepthGuidanceMode.MONOCULAR,
            DepthGuidanceMode.LDAF_ONLY,
            -> rearCameraAnalyzer.isRunning()
            null -> rearCameraAnalyzer.isRunning() || arcorePathGuideCamera.isRunning()
        }
    }

    fun hasRecentFrames(withinMs: Long = 2_000L): Boolean {
        val last = lastFrameAtMs
        return last > 0L && System.currentTimeMillis() - last <= withinMs
    }

    fun activeCapabilities(): DepthHardwareCapabilities? = activeCapabilities

    private suspend fun fallbackToCameraX(
        capabilities: DepthHardwareCapabilities,
        reason: String,
    ): Boolean {
        activeCapabilities = capabilities.copy(
            mode = DepthGuidanceMode.MONOCULAR,
            reason = reason,
        )
        return rearCameraAnalyzer.start()
    }

    private suspend fun waitForFrames(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (lastFrameAtMs > 0L) return true
            delay(50L)
        }
        return lastFrameAtMs > 0L
    }

    companion object {
        private const val TAG = "PathGuideCameraHost"
        private const val ARCORE_FRAME_WAIT_MS = 2_500L
        private const val CAMERAX_FRAME_WAIT_MS = 1_200L
    }
}
