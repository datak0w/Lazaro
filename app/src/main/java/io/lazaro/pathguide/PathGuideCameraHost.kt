package io.lazaro.pathguide

import android.content.Context
import android.util.Log
import androidx.camera.core.ImageProxy
import dagger.hilt.android.qualifiers.ApplicationContext
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
            DepthGuidanceMode.ARCORE_DEPTH -> arcorePathGuideCamera.start()
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
}
