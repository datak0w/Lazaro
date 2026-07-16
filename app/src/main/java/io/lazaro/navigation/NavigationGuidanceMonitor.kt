package io.lazaro.navigation

import android.content.Context
import android.os.Bundle
import dagger.hilt.android.qualifiers.ApplicationContext
import io.lazaro.voice.TextToSpeechManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Traduce notificaciones de Google Maps a tips claros para ciegos
 * (Maps + última visión de acera). IMU afina el giro en PathGuide.
 */
@Singleton
class NavigationGuidanceMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val textToSpeechManager: TextToSpeechManager,
    private val audioCoordinator: NavigationAudioCoordinator,
    private val mapsVisionFusionCoordinator: MapsVisionFusionCoordinator,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val active = AtomicBoolean(false)
    private val mapsSpeechMuted = AtomicBoolean(false)
    private var lastAnnounced: String? = null
    private var lastAnnouncedMs = 0L
    private var lastActionTip: String? = null
    private var speakingMaps = false

    fun startNavigation() {
        active.set(true)
        mapsSpeechMuted.set(false)
        audioCoordinator.startNavigation()
        lastAnnounced = null
        lastAnnouncedMs = 0L
        lastActionTip = null
    }

    fun stopNavigation() {
        active.set(false)
        mapsSpeechMuted.set(false)
        speakingMaps = false
        audioCoordinator.stopNavigation()
        lastAnnounced = null
        lastAnnouncedMs = 0L
        lastActionTip = null
        textToSpeechManager.stop()
    }

    fun isNavigationActive(): Boolean = active.get()

    fun setMapsSpeechMuted(muted: Boolean) {
        mapsSpeechMuted.set(muted)
    }

    fun lastActionTip(): String? = lastActionTip

    fun onMapsNotification(extras: Bundle) {
        if (!active.get()) return
        if (mapsSpeechMuted.get()) return
        if (audioCoordinator.shouldDeferMapsSpeech()) return

        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        val bigText = extras.getCharSequence("android.bigText")?.toString().orEmpty()

        val instruction = MapsNavigationParser.parse(title, text, bigText) ?: return
        if (instruction == lastAnnounced) return

        val instructionType = MapsNavigationParser.classifyInstruction(instruction)
        mapsVisionFusionCoordinator.onMapsInstruction(instructionType, instruction)

        val tip = BlindNavigationPhraseBuilder.announceFromMaps(
            instruction = instruction,
            type = instructionType,
            streetLayout = audioCoordinator.lastStreetLayout(),
        )
        // Evitar repetir el mismo tip de acción en ráfaga (Maps spamea notifs)
        if (tip == lastActionTip && System.currentTimeMillis() - lastAnnouncedMs < MIN_SAME_TIP_MS) {
            lastAnnounced = instruction
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastAnnouncedMs < MIN_ANNOUNCE_INTERVAL_MS) return

        lastAnnounced = instruction
        lastAnnouncedMs = now
        lastActionTip = tip

        scope.launch {
            if (speakingMaps) return@launch
            speakingMaps = true
            try {
                awaitSpeechWindow()
                audioCoordinator.onMapsInstructionStarting(instruction)
                TurnHapticFeedback.pulseForInstruction(context, instruction)
                // Tip Lazaro (acción clara + acera + metros), no el texto crudo de Maps
                textToSpeechManager.speak(tip)
            } finally {
                audioCoordinator.onMapsInstructionFinished()
                speakingMaps = false
            }
        }
    }

    fun onMapsNotificationRemoved() {
        // Maps rota notificaciones con frecuencia; no detenemos la sesión por ello.
    }

    private suspend fun awaitSpeechWindow() {
        var waited = 0L
        while (textToSpeechManager.isSpeaking.value && waited < MAX_SPEECH_WAIT_MS) {
            delay(SPEECH_POLL_MS)
            waited += SPEECH_POLL_MS
        }
    }

    companion object {
        private const val MIN_ANNOUNCE_INTERVAL_MS = 4_500L
        private const val MIN_SAME_TIP_MS = 12_000L
        private const val MAX_SPEECH_WAIT_MS = 2_500L
        private const val SPEECH_POLL_MS = 120L
    }
}
