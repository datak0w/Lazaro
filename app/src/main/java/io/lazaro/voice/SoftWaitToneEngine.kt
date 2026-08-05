package io.lazaro.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tono de “cargando” mientras Lázaro procesa.
 * Pulsos audibles vía [CueAudioPlayer] (mismo eje que TTS).
 */
@Singleton
class SoftWaitToneEngine @Inject constructor(
    private val cueAudioPlayer: CueAudioPlayer,
) {
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var loopJob: Job? = null
    private var startJob: Job? = null
    @Volatile
    private var running = false

    private val pulsePcm: ShortArray by lazy {
        cueAudioPlayer.generateTwoTonePulse(
            durationMs = PULSE_MS,
            freq1Hz = 392f,
            freq2Hz = 523f,
            amplitude = AMPLITUDE,
        )
    }

    /** Arranca tras un pequeño retardo para evitar chirps en respuestas instantáneas. */
    fun startDelayed() {
        if (running) return
        startJob?.cancel()
        startJob = scope.launch {
            delay(START_DELAY_MS)
            if (!isActive) return@launch
            startNow()
        }
    }

    fun startNow() {
        if (running) return
        running = true
        startJob?.cancel()
        startJob = null
        loopJob?.cancel()
        loopJob = scope.launch(Dispatchers.IO) {
            while (isActive && running) {
                cueAudioPlayer.playMonoPcm(pulsePcm)
                if (!running) break
                delay(PULSE_GAP_MS)
            }
        }
    }

    fun stop() {
        running = false
        startJob?.cancel()
        startJob = null
        loopJob?.cancel()
        loopJob = null
    }

    companion object {
        private const val AMPLITUDE = 0.38f
        private const val PULSE_MS = 340
        private const val PULSE_GAP_MS = 520L
        private const val START_DELAY_MS = 200L
    }
}
