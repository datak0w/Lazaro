package io.lazaro.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.sin

/**
 * Reproduce cues PCM cortos por el mismo eje de volumen que TTS/asistencia.
 * Evita USAGE_ASSISTANCE_SONIFICATION (a menudo silenciado en Samsung/Pixel).
 */
@Singleton
class CueAudioPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun playMonoPcm(
        samples: ShortArray,
        sampleRate: Int = SAMPLE_RATE,
        amplitudeScale: Float = 1f,
    ) {
        if (samples.isEmpty()) return
        val scaled = if (amplitudeScale == 1f) {
            samples
        } else {
            ShortArray(samples.size) { i ->
                (samples[i] * amplitudeScale)
                    .toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            }
        }
        if (!playWithUsage(scaled, sampleRate, AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)) {
            playWithUsage(scaled, sampleRate, AudioAttributes.USAGE_MEDIA)
        }
    }

    fun generateToneSweep(
        durationMs: Int,
        freqStartHz: Float,
        freqEndHz: Float,
        amplitude: Float,
    ): ShortArray {
        val n = (SAMPLE_RATE * durationMs / 1000.0).toInt().coerceAtLeast(1)
        val out = ShortArray(n)
        val amp = (Short.MAX_VALUE * amplitude.coerceIn(0.05f, 0.9f)).toInt()
        for (i in 0 until n) {
            val t = i / (n - 1f).coerceAtLeast(1f)
            val freq = freqStartHz + (freqEndHz - freqStartHz) * t
            val env = when {
                i < n * 0.1f -> i / (n * 0.1f)
                i > n * 0.65f -> ((n - i) / (n * 0.35f)).coerceIn(0f, 1f)
                else -> 1f
            }
            out[i] = (sin(2.0 * PI * freq * i / SAMPLE_RATE) * amp * env).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
        return out
    }

    fun generateTwoTonePulse(
        durationMs: Int,
        freq1Hz: Float,
        freq2Hz: Float,
        amplitude: Float,
    ): ShortArray {
        val n = (SAMPLE_RATE * durationMs / 1000.0).toInt().coerceAtLeast(1)
        val out = ShortArray(n)
        val amp = (Short.MAX_VALUE * amplitude.coerceIn(0.05f, 0.9f)).toInt()
        val half = n / 2
        for (i in 0 until n) {
            val freq = if (i < half) freq1Hz else freq2Hz
            val local = if (i < half) i else i - half
            val len = if (i < half) half else n - half
            val env = when {
                local < len * 0.12f -> local / (len * 0.12f)
                local > len * 0.55f -> ((len - local) / (len * 0.45f)).coerceIn(0f, 1f)
                else -> 1f
            }
            out[i] = (sin(2.0 * PI * freq * local / SAMPLE_RATE) * amp * env).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
        return out
    }

    private fun playWithUsage(samples: ShortArray, sampleRate: Int, usage: Int): Boolean {
        val focus = requestFocus(usage)
        return try {
            playStatic(samples, sampleRate, usage) || playStream(samples, sampleRate, usage)
        } finally {
            abandonFocus(focus)
        }
    }

    private fun playStatic(samples: ShortArray, sampleRate: Int, usage: Int): Boolean {
        var track: AudioTrack? = null
        return try {
            val bytes = samples.size * 2
            track = AudioTrack.Builder()
                .setAudioAttributes(attributes(usage))
                .setAudioFormat(monoFormat(sampleRate))
                .setBufferSizeInBytes(bytes.coerceAtLeast(minBuffer(sampleRate)))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setSessionId(AudioManager.AUDIO_SESSION_ID_GENERATE)
                .build()
            @Suppress("DEPRECATION")
            track.setVolume(1f)
            val written = track.write(samples, 0, samples.size)
            if (written < samples.size / 2) {
                Log.w(TAG, "STATIC write corto written=$written expected=${samples.size}")
                return false
            }
            track.play()
            val durationMs = (samples.size * 1000L / sampleRate) + 40L
            Thread.sleep(durationMs)
            true
        } catch (e: Exception) {
            Log.w(TAG, "STATIC falló usage=$usage: ${e.message}")
            false
        } finally {
            releaseTrack(track)
        }
    }

    private fun playStream(samples: ShortArray, sampleRate: Int, usage: Int): Boolean {
        var track: AudioTrack? = null
        return try {
            val min = minBuffer(sampleRate)
            track = AudioTrack.Builder()
                .setAudioAttributes(attributes(usage))
                .setAudioFormat(monoFormat(sampleRate))
                .setBufferSizeInBytes(min.coerceAtLeast(samples.size * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setSessionId(AudioManager.AUDIO_SESSION_ID_GENERATE)
                .build()
            @Suppress("DEPRECATION")
            track.setVolume(1f)
            track.play()
            var offset = 0
            while (offset < samples.size) {
                val n = track.write(samples, offset, samples.size - offset)
                if (n <= 0) break
                offset += n
            }
            val durationMs = (samples.size * 1000L / sampleRate) + 40L
            Thread.sleep(durationMs)
            track.playState == AudioTrack.PLAYSTATE_PLAYING || offset > 0
        } catch (e: Exception) {
            Log.w(TAG, "STREAM falló usage=$usage: ${e.message}")
            false
        } finally {
            releaseTrack(track)
        }
    }

    private fun attributes(usage: Int): AudioAttributes {
        return AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }

    private fun monoFormat(sampleRate: Int): AudioFormat {
        return AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
    }

    private fun minBuffer(sampleRate: Int): Int {
        return AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(sampleRate / 10)
    }

    private fun requestFocus(usage: Int): Any? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attributes(usage))
                    .setOnAudioFocusChangeListener { }
                    .build()
                audioManager.requestAudioFocus(req)
                req
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                )
                FOCUS_LEGACY
            }
        } catch (e: Exception) {
            Log.w(TAG, "AudioFocus falló: ${e.message}")
            null
        }
    }

    private fun abandonFocus(token: Any?) {
        if (token == null) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && token is AudioFocusRequest) {
                audioManager.abandonAudioFocusRequest(token)
            } else if (token === FOCUS_LEGACY) {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseTrack(track: AudioTrack?) {
        if (track == null) return
        try {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
        } catch (_: Exception) {
        }
        try {
            track.release()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "CueAudioPlayer"
        const val SAMPLE_RATE = 22_050
        private val FOCUS_LEGACY = Any()
    }
}
