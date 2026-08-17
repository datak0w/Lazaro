package io.lazaro.cane

import android.util.Log
import io.lazaro.cane.ble.WeWalkObstacleParser
import io.lazaro.voice.TextToSpeechManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Avisos frontales unificados.
 *
 * Sistema nuevo (el STATUS fe45 del bastón llega tarde ~10–40 s):
 * 1) Primario: cámara / profundidad PathGuide → aviso inmediato.
 * 2) Secundario: ultrasonido BLE del bastón solo si la cámara no avisó hace poco.
 *
 * Frases: «Obstáculo enfrente, a medio metro / un metro / …».
 */
@Singleton
class CaneObstacleAlertManager @Inject constructor(
    private val textToSpeechManager: TextToSpeechManager,
    private val sleepModeController: io.lazaro.assistant.SleepModeController,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastBucket: Int? = null
    private var lastAlertMs = 0L
    private var lastCameraAlertMs = 0L
    private var wasClear = true

    fun bind(@Suppress("UNUSED_PARAMETER") caneBleManager: io.lazaro.cane.ble.CaneBleManager) = Unit

    /**
     * Señal rápida de PathGuide (metros o severidad). Preferente.
     */
    fun onCameraFrontal(
        distanceM: Float?,
        severity: Float,
        blocked: Boolean,
    ) {
        val cm = when {
            distanceM != null && distanceM > 0f -> (distanceM * 100f).toInt().coerceIn(5, 300)
            blocked || severity >= 0.45f -> estimateCmFromSeverity(severity)
            else -> return
        }
        // Cámara: umbral un poco más estricto (≤ ~2,2 m) para no spam.
        if (cm >= CAMERA_CLEAR_CM && !blocked && severity < 0.45f) {
            wasClear = true
            return
        }
        if (cm >= CAMERA_CLEAR_CM && !blocked) return
        announce(cm, source = Source.CAMERA, forceEdge = true)
    }

    /** Distancia lenta del bastón (fe45). Solo si la cámara no ha hablado recientemente. */
    fun onDistanceCm(cm: Int) {
        if (cm >= WeWalkObstacleParser.CLEAR_DISTANCE_CM) {
            wasClear = true
            lastBucket = null
            return
        }
        if (!WeWalkObstacleParser.isObstacleDistance(cm)) return
        val now = System.currentTimeMillis()
        if (now - lastCameraAlertMs < CAMERA_PRIORITY_MS) {
            Log.i(TAG, "cane dist=$cm ignorada: cámara ya avisó")
            return
        }
        announce(cm, source = Source.CANE, forceEdge = false)
    }

    private fun announce(cm: Int, source: Source, forceEdge: Boolean) {
        if (sleepModeController.isSleeping()) return
        val bucket = WeWalkObstacleParser.distanceBucket(cm)
        val edgeStart = wasClear || forceEdge && lastBucket == null
        wasClear = false

        val now = System.currentTimeMillis()
        val bucketChanged = lastBucket != null && lastBucket != bucket
        val cooldownOk = now - lastAlertMs >= ALERT_COOLDOWN_MS
        if (!edgeStart && !bucketChanged && !cooldownOk) return
        if (!edgeStart && bucketChanged && now - lastAlertMs < BUCKET_MIN_MS) return

        lastBucket = bucket
        lastAlertMs = now
        if (source == Source.CAMERA) lastCameraAlertMs = now

        val phrase = WeWalkObstacleParser.announceFrontalPhrase(cm)
        scope.launch {
            try {
                textToSpeechManager.stop()
                textToSpeechManager.initialize()
                textToSpeechManager.speak(phrase)
                Log.i(TAG, "announce src=$source edge=$edgeStart bucket=$bucket: $phrase")
            } catch (e: Exception) {
                Log.w(TAG, "announce falló: ${e.message}")
            }
        }
    }

    private fun estimateCmFromSeverity(severity: Float): Int {
        // ~2 m → 0.45, ~0.5 m → 1.0
        val t = severity.coerceIn(0.45f, 1f)
        return (200 - ((t - 0.45f) / 0.55f) * 150).toInt().coerceIn(50, 200)
    }

    private enum class Source { CAMERA, CANE }

    companion object {
        private const val TAG = "CaneObstacleAlert"
        private const val ALERT_COOLDOWN_MS = 5_000L
        private const val BUCKET_MIN_MS = 1_800L
        /** Si la cámara avisó, el bastón no repite en este margen. */
        private const val CAMERA_PRIORITY_MS = 8_000L
        private const val CAMERA_CLEAR_CM = 220
    }
}
