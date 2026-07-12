package io.lazaro.media

enum class MediaCategory(val id: String, val spokenLabel: String) {
    MUSIC("music", "música"),
    NEWS("news", "noticias"),
    RADIO("radio", "radio"),
    PODCAST("podcast", "podcast"),
    VIDEO("video", "vídeo"),
    ;

    companion object {
        fun fromId(id: String): MediaCategory? = entries.find { it.id == id }
    }
}

data class MediaFavorite(
    val packageName: String,
    val label: String,
)
