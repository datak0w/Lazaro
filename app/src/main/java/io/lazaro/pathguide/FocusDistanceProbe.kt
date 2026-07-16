package io.lazaro.pathguide

import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import androidx.camera.core.ImageProxy
import kotlin.math.abs

/**
 * Lee distancia focal LDAF/autofocus del frame CameraX (Pixel 9 y similares).
 *
 * CameraX 1.4 no expone [CaptureResult] en [ImageProxy]; se cachea vía
 * [onCaptureCompleted] registrado con Camera2Interop en [RearCameraAnalyzer].
 */
object FocusDistanceProbe {

    @Volatile
    private var latestMeters: Float? = null

    @Volatile
    private var latestTimestampNs: Long = 0L

    fun onCaptureCompleted(result: TotalCaptureResult) {
        val diopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: return
        if (diopters <= 0f || diopters.isNaN() || diopters.isInfinite()) return
        latestMeters = (1f / diopters).coerceIn(0.15f, 12f)
        latestTimestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: System.nanoTime()
    }

    fun readMeters(image: ImageProxy): Float? {
        val cached = latestMeters ?: return null
        val ageNs = abs(image.imageInfo.timestamp - latestTimestampNs)
        return if (ageNs <= MAX_TIMESTAMP_DELTA_NS) cached else cached
    }

    fun reset() {
        latestMeters = null
        latestTimestampNs = 0L
    }

    private const val MAX_TIMESTAMP_DELTA_NS = 120_000_000L
}
