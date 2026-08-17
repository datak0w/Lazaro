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
 * Avisos cortos de objetos MediaPipe: voz + debounce por categoría/lado.
 * Prioriza personas/vehículos/animales y deja hablar más en navegación.
 */
@Singleton
class PedestrianObjectAnnouncer @Inject constructor(
    private val textToSpeechManager: TextToSpeechManager,
    private val sleepModeController: SleepModeController,
    private val navigationAudioCoordinator: NavigationAudioCoordinator,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastKey: String? = null
    private var lastSpeakMs = 0L
    private var lastSameKeyMs = 0L

    fun reset() {
        lastKey = null
        lastSpeakMs = 0L
        lastSameKeyMs = 0L
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
        if (mode != PathGuideMode.NAVEGACION &&
            mode != PathGuideMode.PASEO &&
            mode != PathGuideMode.RUTA &&
            mode != PathGuideMode.DEBUG
        ) return

        val primary = PedestrianObjectMapper.pickPrimary(detections) ?: return
        val important = PedestrianObjectMapper.isHighPriority(primary.category)
        val urgent = primary.isFrontal || important

        if (!primary.isFrontal && primary.score < (if (important) 0.28f else 0.34f)) return
        if (!primary.isFrontal && !important && primary.areaRatio < MIN_AREA) return

        val key = "${primary.spanish}:${primary.side}"
        val now = System.currentTimeMillis()
        val minGap = if (urgent) MIN_GAP_URGENT_MS else MIN_GAP_MS
        if (now - lastSpeakMs < minGap) return
        if (key == lastKey && now - lastSameKeyMs < SAME_DEBOUNCE_MS) return
        if (!canSpeak(urgent)) return
        if (navigationAudioCoordinator.shouldDuckBeeps() && !urgent) return

        lastKey = key
        lastSpeakMs = now
        lastSameKeyMs = now
        val message = primary.phrase.replaceFirstChar { it.uppercase() } + "."
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
        private const val MIN_GAP_MS = 1_600L
        private const val MIN_GAP_URGENT_MS = 1_200L
        private const val SAME_DEBOUNCE_MS = 3_800L
        private const val MIN_AREA = 0.012f
    }
}
