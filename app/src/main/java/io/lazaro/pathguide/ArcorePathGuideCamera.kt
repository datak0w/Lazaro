package io.lazaro.pathguide

import android.content.Context
import android.media.Image
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Fuente de frames PathGuide vía ARCore (Depth API).
 *
 * Toda la [com.google.ar.core.Session] corre en un único hilo dedicado:
 * crear/resume/update/pause/close en hilos distintos crashea en Pixel.
 */
@Singleton
class ArcorePathGuideCamera @Inject constructor(
    @ApplicationContext private val context: Context,
    private val depthPerceptionProvider: DepthPerceptionProvider,
) {
    private val running = AtomicBoolean(false)
    private var sampler: ArcoreDepthSampler? = null
    private var frameListener: ((ByteArray, Int, Int) -> Unit)? = null
    private var displayRotation: Int = Surface.ROTATION_0

    private val arThread = HandlerThread("ArcorePathGuide").apply { start() }
    private val arHandler = Handler(arThread.looper)

    fun setFrameListener(listener: (gray: ByteArray, width: Int, height: Int) -> Unit) {
        frameListener = listener
    }

    suspend fun start(): Boolean = suspendCancellableCoroutine { cont ->
        if (running.get()) {
            cont.resume(true)
            return@suspendCancellableCoroutine
        }
        displayRotation = currentDisplayRotation()
        arHandler.post {
            if (running.get()) {
                if (cont.isActive) cont.resume(true)
                return@post
            }
            val newSampler = ArcoreDepthSampler { reason ->
                Log.w(TAG, "ARCore depth falló: $reason")
            }
            val ok = try {
                newSampler.start(context)
            } catch (e: Exception) {
                Log.e(TAG, "Excepción iniciando ARCore", e)
                false
            }
            if (!ok) {
                try {
                    newSampler.stop()
                } catch (_: Exception) {
                }
                if (cont.isActive) cont.resume(false)
                return@post
            }
            sampler = newSampler
            depthPerceptionProvider.bindArcoreSampler(newSampler)
            running.set(true)
            if (cont.isActive) cont.resume(true)
            arHandler.post(captureRunnable)
        }
    }

    fun stop() {
        running.set(false)
        arHandler.post {
            depthPerceptionProvider.bindArcoreSampler(null)
            try {
                sampler?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Error parando ARCore", e)
            }
            sampler = null
        }
    }

    fun isRunning(): Boolean = running.get()

    private val captureRunnable: Runnable = object : Runnable {
        override fun run() {
            if (!running.get()) return
            val activeSampler = sampler
            if (activeSampler == null) {
                running.set(false)
                return
            }
            try {
                val frame = activeSampler.updateFrame()
                if (frame != null) {
                    depthPerceptionProvider.publishArcoreSnapshot(activeSampler.snapshot())
                    val cameraImage = try {
                        frame.acquireCameraImage()
                    } catch (e: Exception) {
                        Log.w(TAG, "No se pudo leer imagen de cámara ARCore", e)
                        null
                    }
                    if (cameraImage != null) {
                        try {
                            val grayFrame = ArcoreImageConverter.toUprightGray(
                                cameraImage,
                                displayRotation,
                            )
                            if (grayFrame != null) {
                                frameListener?.invoke(
                                    grayFrame.bytes,
                                    grayFrame.width,
                                    grayFrame.height,
                                )
                            }
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
            } catch (e: Exception) {
                Log.e(TAG, "Error en bucle ARCore", e)
            }
            if (running.get()) {
                arHandler.postDelayed(this, FRAME_INTERVAL_MS)
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
        private const val FRAME_INTERVAL_MS = 33L
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
        val pixelStride = plane.pixelStride.coerceAtLeast(1)
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return null

        val gray = ByteArray(width * height)
        // pixelStride>1 es habitual en Pixel; no asumir Y contiguo.
        for (y in 0 until height) {
            val rowBase = y * rowStride
            val destBase = y * width
            for (x in 0 until width) {
                gray[destBase + x] = buffer.get(rowBase + x * pixelStride)
            }
        }
        return GrayFrame(gray, width, height)
    }
}
