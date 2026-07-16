package io.lazaro.pathguide

import android.content.Context
import android.media.Image
import android.util.Log
import android.view.Surface
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fuente de frames PathGuide vía ARCore (Depth API).
 * Sustituye a CameraX cuando el hardware soporta profundidad ARCore.
 */
@Singleton
class ArcorePathGuideCamera @Inject constructor(
    @ApplicationContext private val context: Context,
    private val depthPerceptionProvider: DepthPerceptionProvider,
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private var sampler: ArcoreDepthSampler? = null
    private var frameListener: ((ByteArray, Int, Int) -> Unit)? = null
    private var displayRotation: Int = Surface.ROTATION_0

    fun setFrameListener(listener: (gray: ByteArray, width: Int, height: Int) -> Unit) {
        frameListener = listener
    }

    suspend fun start(): Boolean = withContext(Dispatchers.Main.immediate) {
        if (running.get()) return@withContext true
        displayRotation = currentDisplayRotation()
        val newSampler = ArcoreDepthSampler { reason ->
            Log.w(TAG, "ARCore depth falló: $reason")
        }
        if (!newSampler.start(context)) {
            newSampler.stop()
            return@withContext false
        }
        sampler = newSampler
        depthPerceptionProvider.bindArcoreSampler(newSampler)
        running.set(true)
        executor.execute { captureLoop() }
        true
    }

    fun stop() {
        running.set(false)
        depthPerceptionProvider.bindArcoreSampler(null)
        sampler?.stop()
        sampler = null
    }

    fun isRunning(): Boolean = running.get()

    private fun captureLoop() {
        while (running.get()) {
            val activeSampler = sampler ?: break
            val frame = activeSampler.updateFrame() ?: continue
            depthPerceptionProvider.publishArcoreSnapshot(activeSampler.snapshot())

            val cameraImage = try {
                frame.acquireCameraImage()
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo leer imagen de cámara ARCore", e)
                continue
            }

            try {
                val grayFrame = ArcoreImageConverter.toUprightGray(cameraImage, displayRotation)
                    ?: continue
                frameListener?.invoke(grayFrame.bytes, grayFrame.width, grayFrame.height)
            } catch (e: Exception) {
                Log.e(TAG, "Error convirtiendo frame ARCore", e)
            } finally {
                try {
                    cameraImage.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun currentDisplayRotation(): Int {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE)
            as? android.hardware.display.DisplayManager
        val display = displayManager?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        return display?.rotation ?: Surface.ROTATION_0
    }

    companion object {
        private const val TAG = "ArcorePathGuideCam"
    }
}

internal object ArcoreImageConverter {

    fun toUprightGray(image: Image, displayRotation: Int): GrayFrame? {
        val raw = extractYPlane(image) ?: return null
        val rotationDegrees = when (displayRotation) {
            Surface.ROTATION_0 -> 90
            Surface.ROTATION_90 -> 0
            Surface.ROTATION_180 -> 270
            Surface.ROTATION_270 -> 180
            else -> 90
        }
        return ImageOrientationNormalizer.rotateGray(raw, rotationDegrees)
    }

    private fun extractYPlane(image: Image): GrayFrame? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return null

        val gray = ByteArray(width * height)
        for (y in 0 until height) {
            val rowBase = y * rowStride
            val destBase = y * width
            for (x in 0 until width) {
                gray[destBase + x] = buffer.get(rowBase + x)
            }
        }
        return GrayFrame(gray, width, height)
    }
}
