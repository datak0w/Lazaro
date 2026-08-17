package io.lazaro.pathguide

import android.util.Log
import io.lazaro.assistant.SleepModeController
import io.lazaro.navigation.NavigationAudioCoordinator
import io.lazaro.voice.TextToSpeechManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Avisos de objetos MediaPipe: solo tras estabilizar, con debounce largo
 * para no saltar de etiqueta cada segundo.
 */
@Singleton
class PedestrianObjectAnnouncer @Inject constructor(
    private val textToSpeechManager: TextToSpeechManager,
    private val sleepModeController: SleepModeController,
    private val navigationAudioCoordinator: NavigationAudioCoordinator,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val stabilizer = ObjectDetectionStabilizer()
    private var lastCategory: String? = null
    private var lastSide: ObjectSide? = null
    private var lastSpeakMs = 0L

    fun reset() {
        stabilizer.reset()
        lastCategory = null
        lastSide = null
        lastSpeakMs = 0L
    }

    fun consider(
        detections: List<PedestrianDetection>,
        mode: PathGuideMode,
        sleepMuted: Boolean,
        announcingOther: Boolean,
        canSpeak: (urgent: Boolean) -> Boolean,
    ) {
        if (sleepMuted || announcingOther) return
        if (sleepModeController.isSleeping()) return
        // No anunciar objetos mientras se graba (solo pitidos / GPS).
        if (mode == PathGuideMode.GRABANDO) return
        if (mode != PathGuideMode.NAVEGACION &&
            mode != PathGuideMode.PASEO &&
            mode != PathGuideMode.RUTA &&
            mode != PathGuideMode.DEBUG
        ) return

        val stable = stabilizer.update(detections) ?: return
        val important = PedestrianObjectMapper.isHighPriority(stable.category)
        val urgent = stable.isFrontal && important

        // Laterales: solo si son importantes y con buen score
        if (!stable.isFrontal) {
            if (!important) return
            if (stable.score < 0.50f || stable.areaRatio < 0.028f) return
        } else if (stable.score < 0.45f) {
            return
        }

        val now = System.currentTimeMillis()
        val sameObject = lastCategory == stable.category
        val sideFlip = lastSide != null && lastSide != stable.side

        // Misma categoría: no repetir pronto (aunque cambie el lado un poco)
        if (sameObject && now - lastSpeakMs < SAME_OBJECT_MS) return
        // Cambio de lado del mismo objeto: ignorar (evita izquierda↔delante)
        if (sameObject && sideFlip && now - lastSpeakMs < SIDE_FLIP_IGNORE_MS) return
        // Cualquier anuncio: gap mínimo
        val minGap = if (urgent) MIN_GAP_URGENT_MS else MIN_GAP_MS
        if (now - lastSpeakMs < minGap) return
        if (!canSpeak(urgent)) return
        if (navigationAudioCoordinator.shouldDuckBeeps() && !urgent) return

        lastCategory = stable.category
        lastSide = stable.side
        lastSpeakMs = now
        val message = stable.phrase.replaceFirstChar { it.uppercase() } + "."
        scope.launch {
            try {
                textToSpeechManager.initialize()
                textToSpeechManager.speak(message)
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo anunciar objeto", e)
            }
        }
    }

    companion object {
        private const val TAG = "MpObjectAnnouncer"
        private const val MIN_GAP_MS = 4_500L
        private const val MIN_GAP_URGENT_MS = 3_200L
        private const val SAME_OBJECT_MS = 12_000L
        private const val SIDE_FLIP_IGNORE_MS = 8_000L
    }
}
