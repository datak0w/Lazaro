package io.lazaro.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.sin

/**
 * Tono suave de “cargando” mientras Lázaro procesa (búsqueda, Gemini, GPS…).
 * Volumen bajo, pulsos lentos; no compite con TTS ni con pitidos de guía.
 */
@Singleton
class SoftWaitToneEngine @Inject constructor() {
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var loopJob: Job? = null
    private var startJob: Job? = null
    private var running = false

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
                playSoftPulse()
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

    private fun playSoftPulse() {
        val sampleRate = SAMPLE_RATE
        val durationMs = PULSE_MS
        val n = (sampleRate * durationMs / 1000.0).toInt().coerceAtLeast(1)
        val stereo = ShortArray(n * 2)
        val amp = (Short.MAX_VALUE * AMPLITUDE).toInt()
        // Dos tonos suaves enlazados (respiración / loader)
        val f1 = 392f // G4
        val f2 = 494f // B4
        val half = n / 2

        for (i in 0 until n) {
            val freq = if (i < half) f1 else f2
            val local = if (i < half) i else i - half
            val len = if (i < half) half else n - half
            val env = when {
                local < len * 0.15 -> local / (len * 0.15f)
                local > len * 0.55 -> ((len - local) / (len * 0.45f)).coerceIn(0f, 1f)
                else -> 1f
            }
            val sample = (sin(2.0 * PI * freq * local / sampleRate) * amp * env).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
            stereo[i * 2] = sample
            stereo[i * 2 + 1] = sample
        }

        var track: AudioTrack? = null
        try {
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setBufferSizeInBytes(stereo.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            track.write(stereo, 0, stereo.size)
            track.play()
            Thread.sleep(durationMs.toLong() + 20)
        } catch (_: Exception) {
        } finally {
            try {
                track?.stop()
                track?.release()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private const val SAMPLE_RATE = 22_050
        private const val AMPLITUDE = 0.055f
        private const val PULSE_MS = 280
        private const val PULSE_GAP_MS = 720L
        private const val START_DELAY_MS = 700L
    }
}
