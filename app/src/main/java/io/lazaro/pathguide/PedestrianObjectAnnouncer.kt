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
        if (primary.score < 0.42f && !primary.isFrontal) return
        if (primary.areaRatio < MIN_AREA && !primary.isFrontal) return

        val key = "${primary.spanish}:${primary.side}"
        val now = System.currentTimeMillis()
        if (now - lastSpeakMs < MIN_GAP_MS) return
        if (key == lastKey && now - lastSameKeyMs < SAME_DEBOUNCE_MS) return
        if (!canSpeak(primary.isFrontal)) return
        if (navigationAudioCoordinator.shouldDuckBeeps() && !primary.isFrontal) return

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
        private const val MIN_GAP_MS = 2_400L
        private const val SAME_DEBOUNCE_MS = 6_500L
        private const val MIN_AREA = 0.028f
    }
}
