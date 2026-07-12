package io.lazaro.media

object MediaAppCatalog {

    val knownApps: List<MediaAppEntry> = listOf(
        // Música
        MediaAppEntry(
            "com.spotify.music", "Spotify", setOf(MediaCategory.MUSIC),
            searchAliases = listOf("spotify"), searchPriority = 1,
        ),
        MediaAppEntry(
            "com.spotify.lite", "Spotify Lite", setOf(MediaCategory.MUSIC),
            searchAliases = listOf("spotify lite", "spotify"), searchPriority = 2,
        ),
        MediaAppEntry(
            "com.google.android.apps.youtube.music", "YouTube Music", setOf(MediaCategory.MUSIC),
            searchAliases = listOf("youtube music", "youtube musica", "yt music"), searchPriority = 1,
        ),
        MediaAppEntry(
            "com.amazon.mp3", "Amazon Music", setOf(MediaCategory.MUSIC),
            searchAliases = listOf("amazon music", "amazon"), searchPriority = 5,
        ),
        MediaAppEntry(
            "com.apple.android.music", "Apple Music", setOf(MediaCategory.MUSIC),
            searchAliases = listOf("apple music"), searchPriority = 5,
        ),
        MediaAppEntry(
            "com.deezer.android.app", "Deezer", setOf(MediaCategory.MUSIC),
            searchAliases = listOf("deezer"), searchPriority = 5,
        ),
        MediaAppEntry(
            "com.soundcloud.android", "SoundCloud", setOf(MediaCategory.MUSIC),
            searchAliases = listOf("soundcloud"), searchPriority = 5,
        ),

        // Noticias
        MediaAppEntry("com.google.android.apps.magazines", "Google Noticias", setOf(MediaCategory.NEWS)),
        MediaAppEntry("com.elpais.elpais", "El País", setOf(MediaCategory.NEWS)),
        MediaAppEntry("com.bbc.newsuk", "BBC News", setOf(MediaCategory.NEWS)),
        MediaAppEntry("com.cnn.mobile.android.phone", "CNN", setOf(MediaCategory.NEWS)),
        MediaAppEntry("com.ft.news", "Financial Times", setOf(MediaCategory.NEWS)),
        MediaAppEntry("com.reddit.frontpage", "Reddit", setOf(MediaCategory.NEWS, MediaCategory.PODCAST)),

        // Radio
        MediaAppEntry("com.cope.copeapp", "COPE", setOf(MediaCategory.RADIO, MediaCategory.NEWS)),
        MediaAppEntry("com.cope.app", "COPE", setOf(MediaCategory.RADIO, MediaCategory.NEWS)),
        MediaAppEntry("com.cope.mobile", "COPE", setOf(MediaCategory.RADIO, MediaCategory.NEWS)),
        MediaAppEntry("es.rtve.play_radio", "RNE Audio", setOf(MediaCategory.RADIO, MediaCategory.PODCAST)),
        MediaAppEntry("es.rtve.radio", "Radio Nacional", setOf(MediaCategory.RADIO)),
        MediaAppEntry("tunein.player", "TuneIn Radio", setOf(MediaCategory.RADIO, MediaCategory.PODCAST)),
        MediaAppEntry("com.iheart.radio", "iHeartRadio", setOf(MediaCategory.RADIO)),

        // Podcast / vídeo
        MediaAppEntry("com.google.android.youtube", "YouTube", setOf(MediaCategory.VIDEO, MediaCategory.MUSIC, MediaCategory.NEWS),
            searchAliases = listOf("youtube", "you tube"), searchPriority = 1),
        MediaAppEntry("com.google.android.apps.podcasts", "Google Podcasts", setOf(MediaCategory.PODCAST)),
        MediaAppEntry("com.spotify.music", "Spotify", setOf(MediaCategory.PODCAST)),
        MediaAppEntry("com.audible.application", "Audible", setOf(MediaCategory.PODCAST)),
    )
}

data class MediaAppEntry(
    val packageName: String,
    val defaultLabel: String,
    val categories: Set<MediaCategory>,
    val searchAliases: List<String> = emptyList(),
    val searchPriority: Int = 10,
)
