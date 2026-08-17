package io.lazaro.assistant

import io.lazaro.voice.TextToSpeechManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Confirmaciones habladas de estado (listo, BLE, dormir, mic) con cooldown
 * para no spamear al usuario ciego.
 */
@Singleton
class BlindStatusSpeaker @Inject constructor(
    private val textToSpeechManager: TextToSpeechManager,
    private val sleepModeController: SleepModeController,
) {
    @Volatile
    private var lastReadyMs = 0L

    @Volatile
    private var lastCaneDisconnectedMs = 0L

    @Volatile
    private var lastCaneReadyMs = 0L

    @Volatile
    private var lastMicFailMs = 0L

    @Volatile
    private var micFailStreak = 0

    suspend fun announceReady(force: Boolean = false) {
        if (sleepModeController.isSleeping() && !force) return
        val now = System.currentTimeMillis()
        if (!force && now - lastReadyMs < READY_COOLDOWN_MS) return
        lastReadyMs = now
        speakQuietly("Lazaro listo.")
    }

    suspend fun announceCaneDisconnected() {
        if (sleepModeController.isSleeping()) return
        val now = System.currentTimeMillis()
        if (now - lastCaneDisconnectedMs < CANE_COOLDOWN_MS) return
        lastCaneDisconnectedMs = now
        speakQuietly("Bastón desconectado. Reconectando.")
    }

    suspend fun announceCaneReady() {
        if (sleepModeController.isSleeping()) return
        val now = System.currentTimeMillis()
        if (now - lastCaneReadyMs < CANE_COOLDOWN_MS) return
        lastCaneReadyMs = now
        speakQuietly("Bastón listo.")
    }

    suspend fun announceEnteringSleep() {
        // sleeping ya es true: hay que bypassear el mute de TTS.
        speakQuietly(
            "De acuerdo. Modo dormir. Di Lázaro despierta para volver.",
            allowDuringSleep = true,
        )
    }

    suspend fun announceWaking() {
        speakQuietly("Despierto.")
    }

    /** Tras N fallos de mic/wake, aviso con cooldown largo. */
    suspend fun noteMicOrWakeFailure() {
        if (sleepModeController.isSleeping()) return
        micFailStreak++
        if (micFailStreak < MIC_FAIL_THRESHOLD) return
        micFailStreak = 0
        val now = System.currentTimeMillis()
        if (now - lastMicFailMs < MIC_FAIL_COOLDOWN_MS) return
        lastMicFailMs = now
        speakQuietly("No oigo. Revisa el micrófono.")
    }

    fun resetMicFailStreak() {
        micFailStreak = 0
    }

    private suspend fun speakQuietly(message: String, allowDuringSleep: Boolean = false) {
        try {
            textToSpeechManager.speak(message, allowDuringSleep = allowDuringSleep)
        } catch (_: Exception) {
            // No bloquear el servicio por un fallo de TTS de estado.
        }
    }

    companion object {
        private const val READY_COOLDOWN_MS = 45_000L
        private const val CANE_COOLDOWN_MS = 20_000L
        private const val MIC_FAIL_THRESHOLD = 3
        private const val MIC_FAIL_COOLDOWN_MS = 120_000L
    }
}
