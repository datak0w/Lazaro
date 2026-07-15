package io.lazaro.receipt

import io.lazaro.actions.ActionResult
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReceiptCheckerAction @Inject constructor(
    private val receiptIntentDetector: ReceiptIntentDetector,
    private val receiptSnapshotCapture: ReceiptSnapshotCapture,
    private val receiptTextRecognizer: ReceiptTextRecognizer,
) {
    suspend fun tryPrepare(userText: String): ActionResult? {
        if (!receiptIntentDetector.detect(userText)) return null

        delay(300L)
        val bitmap = receiptSnapshotCapture.captureReceiptBitmap()
            ?: return ActionResult.Error(
                "No pude usar la cámara. Comprueba el permiso y apunta al ticket con buena luz.",
            )

        val text = receiptTextRecognizer.recognize(bitmap)
        if (text.isBlank()) {
            return ActionResult.Error(
                "No leí texto en el ticket. Acércalo, enderézalo y vuelve a pedírmelo.",
            )
        }

        val analysis = ReceiptParser.analyze(text)
        return ActionResult.Success(analysis.spokenSummary)
    }
}
