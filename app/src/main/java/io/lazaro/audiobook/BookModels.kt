package io.lazaro.audiobook

enum class AudiobookSource(val label: String) {
    GUTENBERG("Project Gutenberg"),
    LIBRIVOX("Librivox"),
}

data class AudiobookOffer(
    val id: String,
    val title: String,
    val authors: String,
    val source: AudiobookSource,
    val textUrl: String?,
    val audioUrl: String?,
    val rssUrl: String?,
) {
    val hasAudio: Boolean get() = !audioUrl.isNullOrBlank() || !rssUrl.isNullOrBlank()
}

data class BookProgress(
    val bookId: String,
    val title: String,
    val charOffset: Int,
    val textUrl: String,
)
