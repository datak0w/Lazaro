package io.lazaro.pathguide

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Object Detector MediaPipe en [RunningMode.LIVE_STREAM] sobre frames de PathGuide.
 * No bloquea el hilo UI; ignora frames si el detector está ocupado.
 */
@Singleton
class MediaPipeLiveObjectDetector @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val lock = Any()
    private var detector: ObjectDetector? = null
    private val started = AtomicBoolean(false)
    private val inFlight = AtomicBoolean(false)
    private val latest = AtomicReference<List<PedestrianDetection>>(emptyList())
    @Volatile
    private var lastSubmitElapsedMs = 0L
    @Volatile
    private var lastTimestampMs = 0L

    fun ensureStarted() {
        if (started.get() && detector != null) return
        synchronized(lock) {
            if (started.get() && detector != null) return
            closeLocked()
            try {
                val options = ObjectDetector.ObjectDetectorOptions.builder()
                    .setBaseOptions(
                        BaseOptions.builder()
                            .setModelAssetPath(MODEL_ASSET)
                            .build(),
                    )
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setMaxResults(6)
                    .setScoreThreshold(SCORE_THRESHOLD)
                    .setCategoryAllowlist(PedestrianObjectMapper.allowedCategories)
                    .setResultListener { result, inputImage ->
                        handleResult(result, inputImage)
                    }
                    .setErrorListener { error ->
                        inFlight.set(false)
                        Log.w(TAG, "MediaPipe object detector: ${error.message}")
                    }
                    .build()
                detector = ObjectDetector.createFromOptions(context, options)
                started.set(true)
                Log.i(TAG, "Object detector LIVE_STREAM listo ($MODEL_ASSET)")
            } catch (e: Exception) {
                started.set(false)
                detector = null
                Log.e(TAG, "No se pudo iniciar MediaPipe Object Detector", e)
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            closeLocked()
        }
        latest.set(emptyList())
        inFlight.set(false)
        lastSubmitElapsedMs = 0L
        lastTimestampMs = 0L
    }

    fun latestDetections(): List<PedestrianDetection> = latest.get()

    fun primaryLabel(): String? =
        PedestrianObjectMapper.pickPrimary(latest.get())?.spanish

    fun frontalBeepBoost(): Float =
        PedestrianObjectMapper.frontalBeepBoost(latest.get())

    /**
     * Envía un frame si ha pasado el intervalo y el detector está libre.
     * [image] en color (CameraX) es preferible; si es null usa el gris (ARCore).
     */
    fun submitFrame(
        image: ImageProxy?,
        gray: ByteArray,
        width: Int,
        height: Int,
    ) {
        if (!started.get()) return
        val elapsed = SystemClock.elapsedRealtime()
        if (elapsed - lastSubmitElapsedMs < MIN_INTERVAL_MS) return
        if (!inFlight.compareAndSet(false, true)) return
        lastSubmitElapsedMs = elapsed

        val bitmap = try {
            if (image != null) {
                YuvToRgbConverter.imageProxyToBitmap(image, maxSide = INPUT_MAX_SIDE)
            } else {
                GrayBitmapConverter.toBitmap(gray, width, height, maxSide = INPUT_MAX_SIDE)
            }
        } catch (e: Exception) {
            inFlight.set(false)
            Log.w(TAG, "Conversión de frame falló", e)
            return
        }
        if (bitmap == null) {
            inFlight.set(false)
            return
        }

        val detectorRef = detector
        if (detectorRef == null) {
            recycleQuietly(bitmap)
            inFlight.set(false)
            return
        }

        val timestampMs = monotonicTimestamp()
        val mpImage = try {
            BitmapImageBuilder(bitmap).build()
        } catch (e: Exception) {
            recycleQuietly(bitmap)
            inFlight.set(false)
            Log.w(TAG, "BitmapImageBuilder falló", e)
            return
        } finally {
            recycleQuietly(bitmap)
        }

        try {
            detectorRef.detectAsync(mpImage, timestampMs)
        } catch (e: Exception) {
            inFlight.set(false)
            closeQuietly(mpImage)
            Log.w(TAG, "detectAsync falló", e)
        }
    }

    private fun handleResult(result: ObjectDetectorResult, inputImage: MPImage) {
        try {
            val imgW = inputImage.width.toFloat().coerceAtLeast(1f)
            val imgH = inputImage.height.toFloat().coerceAtLeast(1f)
            val mapped = result.detections().mapNotNull { detection ->
                val category = detection.categories().firstOrNull() ?: return@mapNotNull null
                val box = detection.boundingBox()
                PedestrianObjectMapper.fromBox(
                    category = category.categoryName(),
                    score = category.score(),
                    left = box.left,
                    top = box.top,
                    right = box.right,
                    bottom = box.bottom,
                    imageWidth = imgW,
                    imageHeight = imgH,
                )?.takeIf { it.score >= SCORE_THRESHOLD }
            }
            latest.set(mapped)
        } catch (e: Exception) {
            Log.w(TAG, "Error interpretando detecciones", e)
        } finally {
            closeQuietly(inputImage)
            inFlight.set(false)
        }
    }

    private fun monotonicTimestamp(): Long {
        val now = SystemClock.uptimeMillis()
        val next = if (now <= lastTimestampMs) lastTimestampMs + 1L else now
        lastTimestampMs = next
        return next
    }

    private fun closeLocked() {
        started.set(false)
        try {
            detector?.close()
        } catch (_: Exception) {
        }
        detector = null
    }

    private fun recycleQuietly(bitmap: Bitmap) {
        try {
            if (!bitmap.isRecycled) bitmap.recycle()
        } catch (_: Exception) {
        }
    }

    private fun closeQuietly(image: MPImage) {
        try {
            image.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "MpObjectDetector"
        const val MODEL_ASSET = "efficientdet_lite0.tflite"
        private const val SCORE_THRESHOLD = 0.38f
        private const val MIN_INTERVAL_MS = 130L
        private const val INPUT_MAX_SIDE = 320
    }
}
