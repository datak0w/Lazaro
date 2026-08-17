package io.lazaro.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tono de “cargando” mientras Lázaro procesa.
 * Se calla al instante si empieza a hablar el TTS (sin solaparse con la voz).
 */
@Singleton
class SoftWaitToneEngine @Inject constructor(
    private val cueAudioPlayer: CueAudioPlayer,
    private val textToSpeechManager: TextToSpeechManager,
) {
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var loopJob: Job? = null
    private var startJob: Job? = null
    private var gateJob: Job? = null
    @Volatile
    private var running = false
    @Volatile
    private var heldForSpeech = false

    private val pulsePcm: ShortArray by lazy {
        cueAudioPlayer.generateTwoTonePulse(
            durationMs = PULSE_MS,
            freq1Hz = 392f,
            freq2Hz = 523f,
            amplitude = AMPLITUDE,
        )
    }

    init {
        gateJob = scope.launch {
            textToSpeechManager.isSpeaking.collectLatest { speaking ->
                if (speaking) {
                    // Voz tiene prioridad absoluta: corta el pulso en curso.
                    heldForSpeech = true
                    cueAudioPlayer.stopCurrent()
                } else if (heldForSpeech) {
                    heldForSpeech = false
                }
            }
        }
    }

    /** Arranca tras un retardo para no chirriar en respuestas instantáneas. */
    fun startDelayed() {
        if (running || textToSpeechManager.isSpeaking.value) return
        startJob?.cancel()
        startJob = scope.launch {
            delay(START_DELAY_MS)
            if (!isActive) return@launch
            if (textToSpeechManager.isSpeaking.value) return@launch
            startNow()
        }
    }

    fun startNow() {
        if (running || textToSpeechManager.isSpeaking.value) return
        running = true
        heldForSpeech = false
        startJob?.cancel()
        startJob = null
        loopJob?.cancel()
        loopJob = scope.launch(Dispatchers.IO) {
            while (isActive && running) {
                if (textToSpeechManager.isSpeaking.value || heldForSpeech) {
                    delay(PULSE_GAP_MS)
                    continue
                }
                cueAudioPlayer.playMonoPcm(pulsePcm)
                if (!running || textToSpeechManager.isSpeaking.value) break
                delay(PULSE_GAP_MS)
            }
        }
    }

    fun stop() {
        running = false
        heldForSpeech = false
        startJob?.cancel()
        startJob = null
        loopJob?.cancel()
        loopJob = null
        cueAudioPlayer.stopCurrent()
    }

    companion object {
        private const val AMPLITUDE = 0.32f
        private const val PULSE_MS = 280
        private const val PULSE_GAP_MS = 700L
        /** Más retardo: evita solaparse con «Un momento» / frases cortas. */
        private const val START_DELAY_MS = 550L
    }
}
