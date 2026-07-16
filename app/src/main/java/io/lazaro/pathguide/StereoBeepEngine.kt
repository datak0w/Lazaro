package io.lazaro.pathguide

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import dagger.hilt.android.qualifiers.ApplicationContext
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

@Singleton
class StereoBeepEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var loopJob: Job? = null
    private var volume = 0.92f
    private var leftProximity = 0f
    private var rightProximity = 0f
    private var doorwayMode = false
    private var continuousMode = false
    private var warningMode = false
    private var guidanceMode = false
    private var paused = false
    private var enabled = false

    private var continuousTrack: AudioTrack? = null
    private var continuousChannel = CHANNEL_NONE
    private var continuousPhase = 0.0
    private var continuousFrequency = 0f
    private var continuousAmplitude = 0f

    private val beepStabilizer = BeepSignalStabilizer()
    private var latchedContinuousSide = BeepSignalStabilizer.SIDE_NONE
    private var continuousLatchSinceMs = 0L

    fun start() {
        if (loopJob?.isActive == true) return
        enabled = true
        loopJob = scope.launch(Dispatchers.IO) {
            while (isActive && enabled) {
                if (!paused) {
                    if (continuousMode) {
                        renderContinuousTone()
                    } else {
                        val side = beepStabilizer.dominantSide(leftProximity, rightProximity)
                        when (side) {
                            BeepSignalStabilizer.SIDE_LEFT ->
                                maybeBeep(channel = CHANNEL_LEFT, leftProximity)
                            BeepSignalStabilizer.SIDE_RIGHT ->
                                maybeBeep(channel = CHANNEL_RIGHT, rightProximity)
                        }
                    }
                } else if (continuousMode) {
                    stopContinuousTone()
                }
                delay(if (continuousMode) 60 else 40)
            }
        }
    }

    fun stop() {
        enabled = false
        loopJob?.cancel()
        loopJob = null
        stopContinuousTone()
        beepStabilizer.reset()
        latchedContinuousSide = BeepSignalStabilizer.SIDE_NONE
        leftProximity = 0f
        rightProximity = 0f
    }

    fun setVolume(value: Float) {
        volume = value.coerceIn(0.1f, 1f)
    }

    fun update(
        left: Float,
        right: Float,
        doorwayMode: Boolean = false,
        continuousTone: Boolean = false,
        warningMode: Boolean = false,
        guidanceMode: Boolean = false,
    ) {
        this.doorwayMode = doorwayMode
        this.warningMode = warningMode
        this.guidanceMode = guidanceMode
        continuousMode = continuousTone || warningMode || guidanceMode
        // Guía continua L/R (acera/giro): no matar la señal con dead-zone del estabilizador.
        if (continuousMode) {
            leftProximity = left.coerceIn(0f, 1f)
            rightProximity = right.coerceIn(0f, 1f)
        } else {
            val (stableLeft, stableRight) = beepStabilizer.stabilize(left, right, doorwayMode)
            leftProximity = stableLeft
            rightProximity = stableRight
            stopContinuousTone()
            latchedContinuousSide = BeepSignalStabilizer.SIDE_NONE
        }
    }

    fun setPaused(value: Boolean) {
        paused = value
        if (value) stopContinuousTone()
    }

    /**
     * Tono de éxito tipo "coin" (Super Mario): dos notas ascendentes rápidas en ambos oídos.
     * Se dispara al alinear el ángulo IMU correcto.
     */
    fun playSuccessCoin() {
        scope.launch(Dispatchers.IO) {
            val wasPaused = paused
            paused = true
            stopContinuousTone()
            try {
                playCoinChirp()
            } finally {
                paused = wasPaused
            }
        }
    }

    private fun playCoinChirp() {
        val sampleRate = SAMPLE_RATE
        val notes = listOf(
            988f to 70,   // B5
            1319f to 140, // E6
        )
        val totalMs = notes.sumOf { it.second } + 20
        val totalSamples = (sampleRate * totalMs / 1000.0).toInt()
        val stereo = ShortArray(totalSamples * 2)
        var offset = 0
        val amp = (Short.MAX_VALUE * volume * 0.42f).toInt()

        for ((freq, durMs) in notes) {
            val n = (sampleRate * durMs / 1000.0).toInt()
            for (i in 0 until n) {
                if (offset + i >= totalSamples) break
                val env = when {
                    i < n * 0.08 -> i / (n * 0.08f)
                    i > n * 0.7 -> ((n - i) / (n * 0.3f)).coerceIn(0f, 1f)
                    else -> 1f
                }
                val sample = (sin(2.0 * PI * freq * i / sampleRate) * amp * env).toInt().toShort()
                val idx = (offset + i) * 2
                stereo[idx] = sample
                stereo[idx + 1] = sample
            }
            offset += n
        }

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
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

        try {
            track.write(stereo, 0, stereo.size)
            track.play()
            Thread.sleep(totalMs.toLong() + 15)
        } catch (_: Exception) {
        } finally {
            try {
                track.stop()
                track.release()
            } catch (_: Exception) {
            }
        }
    }

    private var lastLeftBeepMs = 0L
    private var lastRightBeepMs = 0L

    private fun renderContinuousTone() {
        val threshold = if (guidanceMode) GUIDANCE_THRESHOLD else CONTINUOUS_THRESHOLD
        val leftActive = leftProximity >= threshold
        val rightActive = rightProximity >= threshold

        if (!leftActive && !rightActive) {
            stopContinuousTone()
            latchedContinuousSide = BeepSignalStabilizer.SIDE_NONE
            return
        }

        val targetSide = when {
            leftActive && !rightActive -> BeepSignalStabilizer.SIDE_LEFT
            rightActive && !leftActive -> BeepSignalStabilizer.SIDE_RIGHT
            leftProximity > rightProximity + CONTINUOUS_SWITCH_MARGIN ->
                BeepSignalStabilizer.SIDE_LEFT
            rightProximity > leftProximity + CONTINUOUS_SWITCH_MARGIN ->
                BeepSignalStabilizer.SIDE_RIGHT
            // Ambos lados activos (p. ej. calzada sin lado seguro): mantener oír-preferido
            latchedContinuousSide != BeepSignalStabilizer.SIDE_NONE -> latchedContinuousSide
            leftProximity >= rightProximity -> BeepSignalStabilizer.SIDE_LEFT
            else -> BeepSignalStabilizer.SIDE_RIGHT
        }

        val now = System.currentTimeMillis()
        when {
            latchedContinuousSide == BeepSignalStabilizer.SIDE_NONE -> {
                latchedContinuousSide = targetSide
                continuousLatchSinceMs = now
            }
            targetSide != latchedContinuousSide &&
                now - continuousLatchSinceMs >= if (guidanceMode) GUIDANCE_HOLD_MS else CONTINUOUS_HOLD_MS -> {
                latchedContinuousSide = targetSide
                continuousLatchSinceMs = now
                stopContinuousTone()
            }
        }

        val channel = if (latchedContinuousSide == BeepSignalStabilizer.SIDE_LEFT) {
            CHANNEL_LEFT
        } else {
            CHANNEL_RIGHT
        }
        val proximity = if (channel == CHANNEL_LEFT) leftProximity else rightProximity
        val targetFrequency = if (warningMode) {
            lerp(WARNING_MIN_FREQ, WARNING_MAX_FREQ, proximity)
        } else if (guidanceMode) {
            lerp(GUIDANCE_MIN_FREQ, GUIDANCE_MAX_FREQ, proximity)
        } else {
            lerp(CONTINUOUS_MIN_FREQ, CONTINUOUS_MAX_FREQ, proximity)
        }
        val targetAmplitude = if (warningMode) {
            lerp(0.45f, 0.72f, proximity) * volume
        } else if (guidanceMode) {
            lerp(0.18f, 0.48f, proximity) * volume
        } else {
            lerp(0.38f, 0.62f, proximity) * volume
        }

        if (warningMode) {
            writeWarningChunk(targetFrequency, targetAmplitude)
            return
        }

        if (channel != continuousChannel) {
            continuousChannel = channel
            continuousPhase = 0.0
        }

        continuousFrequency = targetFrequency
        continuousAmplitude = targetAmplitude
        writeContinuousChunk()
    }

    private fun writeWarningChunk(frequency: Float, amplitude: Float) {
        val track = ensureContinuousTrack() ?: return
        val sampleRate = SAMPLE_RATE
        val chunkSamples = (sampleRate * CHUNK_MS / 1000.0).toInt()
        val stereo = ShortArray(chunkSamples * 2)
        val sampleAmplitude = (Short.MAX_VALUE * amplitude).toInt()
        val phaseIncrement = 2.0 * PI * frequency / sampleRate

        for (i in 0 until chunkSamples) {
            val sample = (sin(continuousPhase) * sampleAmplitude).toInt().toShort()
            continuousPhase += phaseIncrement
            if (continuousPhase > 2.0 * PI) continuousPhase -= 2.0 * PI
            val idx = i * 2
            stereo[idx] = sample
            stereo[idx + 1] = sample
        }

        try {
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                track.play()
            }
            track.write(stereo, 0, stereo.size)
        } catch (_: Exception) {
            stopContinuousTone()
        }
    }

    private fun writeContinuousChunk() {
        val track = ensureContinuousTrack() ?: return
        val sampleRate = SAMPLE_RATE
        val chunkSamples = (sampleRate * CHUNK_MS / 1000.0).toInt()
        val stereo = ShortArray(chunkSamples * 2)
        val amplitude = (Short.MAX_VALUE * continuousAmplitude).toInt()
        val phaseIncrement = 2.0 * PI * continuousFrequency / sampleRate

        for (i in 0 until chunkSamples) {
            val sample = (sin(continuousPhase) * amplitude).toInt().toShort()
            continuousPhase += phaseIncrement
            if (continuousPhase > 2.0 * PI) continuousPhase -= 2.0 * PI

            val idx = i * 2
            if (continuousChannel == CHANNEL_LEFT) {
                stereo[idx] = sample
                stereo[idx + 1] = 0
            } else {
                stereo[idx] = 0
                stereo[idx + 1] = sample
            }
        }

        try {
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                track.play()
            }
            track.write(stereo, 0, stereo.size)
        } catch (_: Exception) {
            stopContinuousTone()
        }
    }

    private fun ensureContinuousTrack(): AudioTrack? {
        if (continuousTrack != null) return continuousTrack

        val sampleRate = SAMPLE_RATE
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = maxOf(minBuffer, SAMPLE_RATE / 4) * 2

        continuousTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
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
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        return continuousTrack
    }

    private fun stopContinuousTone() {
        continuousChannel = CHANNEL_NONE
        continuousFrequency = 0f
        continuousAmplitude = 0f
        latchedContinuousSide = BeepSignalStabilizer.SIDE_NONE
        try {
            continuousTrack?.pause()
            continuousTrack?.flush()
            continuousTrack?.stop()
            continuousTrack?.release()
        } catch (_: Exception) {
        }
        continuousTrack = null
    }

    private fun maybeBeep(channel: Int, proximity: Float) {
        val threshold = if (doorwayMode) DOORWAY_BEEP_THRESHOLD else BEEP_THRESHOLD
        if (proximity < threshold) return

        val now = System.currentTimeMillis()
        val minInterval = if (doorwayMode) 650 else 700
        val maxInterval = if (doorwayMode) 120 else 150
        val minFreq = if (doorwayMode) 560f else 500f
        val maxFreq = if (doorwayMode) 1100f else 1100f

        val t = ((proximity - threshold) / (1f - threshold)).coerceIn(0f, 1f)
        val interval = lerp(minInterval, maxInterval, t)
        val last = if (channel == CHANNEL_LEFT) lastLeftBeepMs else lastRightBeepMs
        if (now - last < interval) return

        if (channel == CHANNEL_LEFT) lastLeftBeepMs = now else lastRightBeepMs = now

        val frequency = lerp(minFreq, maxFreq, t)
        val duration = if (doorwayMode) 80 else 90
        playDiscreteTone(channel, frequency, durationMs = duration)
    }

    private fun playDiscreteTone(channel: Int, frequency: Float, durationMs: Int) {
        val sampleRate = SAMPLE_RATE
        val samples = (sampleRate * durationMs / 1000.0).toInt()
        val stereo = ShortArray(samples * 2)
        val amplitude = (Short.MAX_VALUE * volume * 0.48f).toInt()

        for (i in 0 until samples) {
            val sample = (sin(2.0 * PI * frequency * i / sampleRate) * amplitude).toInt().toShort()
            val idx = i * 2
            if (channel == CHANNEL_LEFT) {
                stereo[idx] = sample
                stereo[idx + 1] = 0
            } else {
                stereo[idx] = 0
                stereo[idx + 1] = sample
            }
        }

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
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

        try {
            track.write(stereo, 0, stereo.size)
            track.play()
            Thread.sleep(durationMs.toLong() + 10)
        } catch (_: Exception) {
        } finally {
            try {
                track.stop()
                track.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun lerp(start: Int, end: Int, t: Float): Long {
        return (start + (end - start) * t.coerceIn(0f, 1f)).toLong()
    }

    private fun lerp(start: Float, end: Float, t: Float): Float {
        return start + (end - start) * t.coerceIn(0f, 1f)
    }

    companion object {
        private const val SAMPLE_RATE = 44_100
        private const val CHUNK_MS = 90
        private const val CHANNEL_LEFT = 0
        private const val CHANNEL_RIGHT = 1
        private const val CHANNEL_NONE = -1
        private const val BEEP_THRESHOLD = 0.12f
        private const val DOORWAY_BEEP_THRESHOLD = 0.14f
        private const val CONTINUOUS_THRESHOLD = 0.08f
        private const val GUIDANCE_THRESHOLD = 0.05f
        private const val CONTINUOUS_SWITCH_MARGIN = 0.14f
        private const val CONTINUOUS_HOLD_MS = 850L
        private const val GUIDANCE_HOLD_MS = 550L
        private const val CONTINUOUS_MIN_FREQ = 520f
        private const val CONTINUOUS_MAX_FREQ = 980f
        private const val GUIDANCE_MIN_FREQ = 420f
        private const val GUIDANCE_MAX_FREQ = 760f
        private const val WARNING_MIN_FREQ = 980f
        private const val WARNING_MAX_FREQ = 1480f
    }
}
