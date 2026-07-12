package io.lazaro.media

import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaIntentDetector @Inject constructor() {

    fun detect(userText: String): MediaCategory? {
        val text = normalize(userText)
        if (text.isBlank()) return null

        return when {
            matchesAny(text, MUSIC_TRIGGERS) -> MediaCategory.MUSIC
            matchesAny(text, NEWS_APP_TRIGGERS) -> MediaCategory.NEWS
            matchesAny(text, RADIO_TRIGGERS) -> MediaCategory.RADIO
            matchesAny(text, PODCAST_TRIGGERS) -> MediaCategory.PODCAST
            matchesAny(text, VIDEO_TRIGGERS) -> MediaCategory.VIDEO
            else -> null
        }
    }

    private fun matchesAny(text: String, triggers: List<String>): Boolean {
        return triggers.any { trigger ->
            text.contains(trigger) ||
                text.matches(Regex(""".*\b$trigger\b.*"""))
        }
    }

    private fun normalize(text: String): String {
        val withoutAccents = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        return withoutAccents
            .lowercase()
            .replace("lazaro", " ")
            .replace("lázaro", " ")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    companion object {
        private val MUSIC_TRIGGERS = listOf(
            "pon musica", "pon la musica", "ponme musica", "reproduce musica",
            "abre musica", "musica", "cancion", "canciones", "spotify",
        )
        private val NEWS_APP_TRIGGERS = listOf(
            "abre noticias", "abre las noticias", "app de noticias", "noticias en",
            "abre el pais", "abre cnn", "google noticias",
        )
        private val RADIO_TRIGGERS = listOf(
            "pon la radio", "pon radio", "radio", "escuchar radio", "la cope", "rne",
        )
        private val PODCAST_TRIGGERS = listOf(
            "pon un podcast", "podcast", "podcasts",
        )
        private val VIDEO_TRIGGERS = listOf(
            "pon youtube", "youtube", "pon video", "pon un video", "videos",
        )
    }
}
