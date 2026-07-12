package io.lazaro.audiobook

import android.media.AudioAttributes
import android.media.MediaPlayer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudiobookPlayer @Inject constructor() {
    private var mediaPlayer: MediaPlayer? = null

    fun isPlaying(): Boolean = mediaPlayer?.isPlaying == true

    fun playUrl(
        url: String,
        onComplete: () -> Unit = {},
        onError: (String) -> Unit = {},
    ) {
        stop()
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            setDataSource(url)
            setOnCompletionListener {
                onComplete()
                stop()
            }
            setOnErrorListener { _, _, _ ->
                onError("No pude reproducir el audiolibro.")
                stop()
                true
            }
            prepareAsync()
            setOnPreparedListener { start() }
        }
    }

    fun stop() {
        mediaPlayer?.runCatching {
            stop()
            release()
        }
        mediaPlayer = null
    }
}
