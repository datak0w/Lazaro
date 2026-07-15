package io.lazaro.pathguide

import android.content.Context
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class RearCameraAnalyzer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var lifecycleOwner = CameraLifecycleOwner()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var running = false
    private var frameListener: ((ByteArray, Int, Int, ImageProxy) -> Unit)? = null
    @Volatile
    private var snapshotWaiter: CompletableDeferred<GrayFrame>? = null

    suspend fun captureBitmapSnapshot(timeoutMs: Long = 2_500L): Bitmap? = withContext(Dispatchers.Default) {
        val frame = captureGraySnapshotInternal(timeoutMs) ?: return@withContext null
        GrayBitmapConverter.toBitmap(frame.bytes, frame.width, frame.height)
    }

    private suspend fun captureGraySnapshotInternal(timeoutMs: Long = 2_500L): GrayFrame? {
        val deferred = CompletableDeferred<GrayFrame>()
        snapshotWaiter = deferred
        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            if (snapshotWaiter === deferred) {
                snapshotWaiter = null
            }
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                updateTargetRotation()
            }
        }
    }

    fun setFrameListener(listener: (ByteArray, Int, Int, ImageProxy) -> Unit) {
        frameListener = listener
    }

    suspend fun start(): Boolean = withContext(Dispatchers.Main.immediate) {
        if (running) return@withContext true
        return@withContext try {
            val provider = obtainCameraProvider()
            cameraProvider = provider
            lifecycleOwner.start()

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .setTargetRotation(currentDisplayRotation())
                .build()
            imageAnalysis = analysis

            analysis.setAnalyzer(executor) { image ->
                processFrame(image)
            }

            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                analysis,
            )
            registerDisplayListener()
            running = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo iniciar la cámara trasera", e)
            stop()
            false
        }
    }

    fun stop() {
        running = false
        unregisterDisplayListener()
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
        }
        cameraProvider = null
        imageAnalysis = null
        lifecycleOwner.stop()
        lifecycleOwner = CameraLifecycleOwner()
    }

    fun isRunning(): Boolean = running

    private fun processFrame(image: ImageProxy) {
        try {
            val frame = ImageOrientationNormalizer.toUprightGray(image)
            if (frame != null) {
                val waiter = snapshotWaiter
                if (waiter != null && waiter.isActive) {
                    waiter.complete(
                        GrayFrame(
                            bytes = frame.bytes.copyOf(),
                            width = frame.width,
                            height = frame.height,
                        ),
                    )
                    snapshotWaiter = null
                    image.close()
                    return
                }
                frameListener?.invoke(frame.bytes, frame.width, frame.height, image)
                return
            }
            image.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error procesando frame de cámara", e)
            try {
                image.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun updateTargetRotation() {
        val analysis = imageAnalysis ?: return
        try {
            analysis.targetRotation = currentDisplayRotation()
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo actualizar rotación de cámara", e)
        }
    }

    private fun currentDisplayRotation(): Int {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
        return display?.rotation ?: Surface.ROTATION_0
    }

    private fun registerDisplayListener() {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            displayManager.registerDisplayListener(displayListener, null)
        }
    }

    private fun unregisterDisplayListener() {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            try {
                displayManager.unregisterDisplayListener(displayListener)
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun obtainCameraProvider(): ProcessCameraProvider {
        return suspendCancellableCoroutine { continuation ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                {
                    try {
                        continuation.resume(future.get())
                    } catch (e: Exception) {
                        continuation.resumeWithException(e)
                    }
                },
                ContextCompat.getMainExecutor(context),
            )
        }
    }

    companion object {
        private const val TAG = "RearCameraAnalyzer"
    }
}
