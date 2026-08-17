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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Object Detector MediaPipe en [RunningMode.LIVE_STREAM].
 * Corre fuera del hilo del analyzer de CameraX para no tumbar frames/debug.
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
    private val pendingBitmap = AtomicReference<Bitmap?>(null)
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "MpObjectDetect").apply { isDaemon = true }
    }

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
                        recyclePendingBitmap()
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
     * Copia el gray y procesa en background. No usa [ImageProxy] (evita JPEG pesado
     * y conflictos de ciclo de vida en el analyzer).
     */
    fun submitFrame(
        image: ImageProxy?,
        gray: ByteArray,
        width: Int,
        height: Int,
    ) {
        if (!started.get() || detector == null) return
        val elapsed = SystemClock.elapsedRealtime()
        if (elapsed - lastSubmitElapsedMs < MIN_INTERVAL_MS) return
        if (!inFlight.compareAndSet(false, true)) return
        lastSubmitElapsedMs = elapsed

        // Copia inmediata: el gray del analyzer puede reutilizarse.
        val grayCopy = try {
            gray.copyOf()
        } catch (e: Exception) {
            inFlight.set(false)
            return
        }
        // image no se usa en background (puede cerrarse en finally del controller).
        executor.execute {
            runDetection(grayCopy, width, height)
        }
    }

    private fun runDetection(gray: ByteArray, width: Int, height: Int) {
        var bitmap: Bitmap? = null
        var mpImage: MPImage? = null
        try {
            bitmap = GrayBitmapConverter.toBitmap(gray, width, height, maxSide = INPUT_MAX_SIDE)
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
            // Ownership: MediaPipe puede leer el bitmap hasta el result/error listener.
            pendingBitmap.set(bitmap)
            mpImage = BitmapImageBuilder(bitmap).build()
            bitmap = null
            detectorRef.detectAsync(mpImage, monotonicTimestamp())
            mpImage = null
        } catch (e: Exception) {
            inFlight.set(false)
            Log.w(TAG, "detectAsync falló", e)
            closeQuietly(mpImage)
            recycleQuietly(bitmap)
            recyclePendingBitmap()
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
            recyclePendingBitmap()
            inFlight.set(false)
        }
    }

    private fun recyclePendingBitmap() {
        recycleQuietly(pendingBitmap.getAndSet(null))
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

    private fun recycleQuietly(bitmap: Bitmap?) {
        if (bitmap == null) return
        try {
            if (!bitmap.isRecycled) bitmap.recycle()
        } catch (_: Exception) {
        }
    }

    private fun closeQuietly(image: MPImage?) {
        if (image == null) return
        try {
            image.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "MpObjectDetector"
        const val MODEL_ASSET = "efficientdet_lite0.tflite"
        private const val SCORE_THRESHOLD = 0.38f
        private const val MIN_INTERVAL_MS = 160L
        private const val INPUT_MAX_SIDE = 320
    }
}
