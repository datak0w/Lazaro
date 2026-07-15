package io.lazaro.receipt

import io.lazaro.pathguide.RearCameraAnalyzer
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReceiptSnapshotCapture @Inject constructor(
    private val rearCameraAnalyzer: RearCameraAnalyzer,
) {
    suspend fun captureReceiptBitmap(): android.graphics.Bitmap? {
        val startedHere = !rearCameraAnalyzer.isRunning()
        if (startedHere) {
            if (!rearCameraAnalyzer.start()) return null
            delay(900L)
        }
        val bitmap = rearCameraAnalyzer.captureBitmapSnapshot()
        if (startedHere) {
            rearCameraAnalyzer.stop()
        }
        return bitmap
    }
}
