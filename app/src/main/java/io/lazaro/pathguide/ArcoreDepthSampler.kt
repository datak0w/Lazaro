package io.lazaro.pathguide

import android.media.Image
import android.util.Log
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException

/**
 * Sesión ARCore con Depth API para guía exterior.
 *
 * Nota: Shared Camera de ARCore **no** permite Depth API; por eso ARCore posee
 * la cámara directamente cuando el modo es [DepthGuidanceMode.ARCORE_DEPTH].
 */
internal class ArcoreDepthSampler(
    private val onSessionFailed: (String) -> Unit,
) {
    private var session: Session? = null
    private var lastProfile: DepthColumnProfile? = null
    private var lastFrontalM: Float? = null
    private var running = false

    val isRunning: Boolean
        get() = running

    fun start(context: android.content.Context): Boolean {
        if (running) return true
        return try {
            val newSession = Session(context)
            val config = newSession.config.apply {
                depthMode = Config.DepthMode.AUTOMATIC
                focusMode = Config.FocusMode.AUTO
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            }
            if (!newSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                newSession.close()
                onSessionFailed("Depth API no soportada")
                return false
            }
            newSession.configure(config)
            newSession.resume()
            session = newSession
            running = true
            true
        } catch (_: UnavailableArcoreNotInstalledException) {
            onSessionFailed("ARCore no instalado")
            false
        } catch (_: UnavailableApkTooOldException) {
            onSessionFailed("ARCore desactualizado")
            false
        } catch (_: UnavailableDeviceNotCompatibleException) {
            onSessionFailed("Dispositivo incompatible con ARCore")
            false
        } catch (_: CameraNotAvailableException) {
            onSessionFailed("Cámara no disponible para ARCore")
            false
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo iniciar sesión ARCore", e)
            onSessionFailed(e.message ?: "Error ARCore")
            false
        }
    }

    fun updateFrame(): Frame? {
        val active = session ?: return null
        return try {
            val frame = active.update()
            lastProfile = sampleDepthProfile(frame)
            lastFrontalM = lastProfile?.let { frontalDistanceFromProfile(it) }
            frame
        } catch (e: Exception) {
            Log.w(TAG, "Error actualizando frame ARCore", e)
            null
        }
    }

    fun snapshot(): DepthSnapshot {
        val profile = lastProfile
        return DepthSnapshot(
            profile = profile,
            frontalDistanceM = lastFrontalM,
            source = if (profile != null) PerceptionSource.DEPTH else PerceptionSource.MONOCULAR,
            available = profile != null || lastFrontalM != null,
        )
    }

    fun stop() {
        running = false
        lastProfile = null
        lastFrontalM = null
        try {
            session?.pause()
            session?.close()
        } catch (_: Exception) {
        }
        session = null
    }

    private fun sampleDepthProfile(frame: Frame): DepthColumnProfile? {
        val depthImage = try {
            frame.acquireDepthImage16Bits()
        } catch (e: Exception) {
            return null
        }

        return try {
            buildProfile(depthImage)
        } finally {
            depthImage.close()
        }
    }

    private fun buildProfile(depthImage: Image): DepthColumnProfile? {
        val width = depthImage.width
        val height = depthImage.height
        if (width < 16 || height < 16) return null

        val plane = depthImage.planes.firstOrNull() ?: return null
        val buffer = plane.buffer.duplicate()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        val cols = COLUMN_COUNT
        val depthMm = FloatArray(cols)
        val confidence = FloatArray(cols)
        val roiTop = (height * ROI_TOP).toInt().coerceIn(0, height - 2)
        val roiBottom = (height * ROI_BOTTOM).toInt().coerceIn(roiTop + 1, height - 1)

        for (col in 0 until cols) {
            val x = ((col + 0.5f) / cols * width).toInt().coerceIn(0, width - 1)
            var sum = 0f
            var count = 0
            var y = roiTop
            while (y <= roiBottom) {
                val index = y * rowStride + x * pixelStride
                if (index + 1 < buffer.limit()) {
                    val mm = readDepthMm(buffer, index)
                    if (mm in MIN_DEPTH_MM..MAX_DEPTH_MM) {
                        sum += mm
                        count++
                    }
                }
                y += SAMPLE_STEP_Y
            }
            if (count > 0) {
                depthMm[col] = sum / count
                confidence[col] = (count / ((roiBottom - roiTop) / SAMPLE_STEP_Y + 1).toFloat())
                    .coerceIn(0.35f, 1f)
            }
        }

        val valid = confidence.count { it >= 0.35f }
        if (valid < cols / 4) return null
        return DepthColumnProfile(cols, depthMm, confidence)
    }

    private fun readDepthMm(buffer: java.nio.ByteBuffer, index: Int): Float {
        val low = buffer.get(index).toInt() and 0xFF
        val high = buffer.get(index + 1).toInt() and 0xFF
        return (low or (high shl 8)).toFloat()
    }

    private fun frontalDistanceFromProfile(profile: DepthColumnProfile): Float? {
        val centerStart = (profile.columns * 0.42f).toInt()
        val centerEnd = (profile.columns * 0.58f).toInt().coerceAtMost(profile.columns - 1)
        val samples = (centerStart..centerEnd).mapNotNull { index ->
            val depth = profile.depthMm[index]
            if (depth > 0f && profile.confidence[index] >= 0.35f) depth else null
        }
        return samples.minOrNull()?.div(1000f)?.coerceIn(0.15f, 12f)
    }

    companion object {
        private const val TAG = "ArcoreDepthSampler"
        private const val COLUMN_COUNT = 32
        private const val ROI_TOP = 0.42f
        private const val ROI_BOTTOM = 0.88f
        private const val SAMPLE_STEP_Y = 4
        private const val MIN_DEPTH_MM = 150f
        private const val MAX_DEPTH_MM = 12_000f
    }
}
