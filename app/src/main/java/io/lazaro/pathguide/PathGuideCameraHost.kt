package io.lazaro.pathguide

import android.util.Log
import androidx.camera.core.ImageProxy
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Elige y gestiona la fuente de cámara para PathGuide según hardware detectado.
 */
@Singleton
class PathGuideCameraHost @Inject constructor(
    private val rearCameraAnalyzer: RearCameraAnalyzer,
    private val arcorePathGuideCamera: ArcorePathGuideCamera,
    private val depthHardwareDetector: DepthHardwareDetector,
) {
    private var activeCapabilities: DepthHardwareCapabilities? = null
    private var frameListener: ((ByteArray, Int, Int, ImageProxy?) -> Unit)? = null

    fun setFrameListener(listener: (ByteArray, Int, Int, ImageProxy?) -> Unit) {
        frameListener = listener
        rearCameraAnalyzer.setFrameListener { gray, width, height, image ->
            listener(gray, width, height, image)
        }
        arcorePathGuideCamera.setFrameListener { gray, width, height ->
            listener(gray, width, height, null)
        }
    }

    suspend fun start(depthEnhancedEnabled: Boolean): Boolean {
        val capabilities = depthHardwareDetector.detect(depthEnhancedEnabled)
        activeCapabilities = capabilities
        return when (capabilities.mode) {
            DepthGuidanceMode.ARCORE_DEPTH -> {
                val arOk = try {
                    arcorePathGuideCamera.start()
                } catch (e: Exception) {
                    Log.e(TAG, "ARCore start lanzó excepción; fallback CameraX", e)
                    false
                }
                if (arOk) {
                    true
                } else {
                    Log.w(TAG, "ARCore no arrancó; usando CameraX monocular")
                    activeCapabilities = capabilities.copy(
                        mode = DepthGuidanceMode.MONOCULAR,
                        reason = "ARCore falló al abrir; fallback CameraX",
                    )
                    rearCameraAnalyzer.start()
                }
            }
            DepthGuidanceMode.MONOCULAR,
            DepthGuidanceMode.LDAF_ONLY,
            -> rearCameraAnalyzer.start()
        }
    }

    fun stop() {
        rearCameraAnalyzer.stop()
        arcorePathGuideCamera.stop()
        activeCapabilities = null
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

    fun activeCapabilities(): DepthHardwareCapabilities? = activeCapabilities

    companion object {
        private const val TAG = "PathGuideCameraHost"
    }
}
