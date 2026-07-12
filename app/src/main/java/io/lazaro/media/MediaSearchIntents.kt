package io.lazaro.media

import android.content.Intent
import android.net.Uri

object MediaSearchIntents {

    fun candidates(packageName: String, query: String): List<Intent> {
        val encoded = Uri.encode(query)
        return when (packageName) {
            "com.spotify.music", "com.spotify.lite" -> listOf(
                viewIntent("spotify:search:$query", packageName),
                viewIntent("https://open.spotify.com/search/$encoded", packageName),
            )
            "com.google.android.youtube" -> listOf(
                Intent(Intent.ACTION_SEARCH).apply {
                    setPackage(packageName)
                    putExtra("query", query)
                },
                viewIntent("https://www.youtube.com/results?search_query=$encoded", packageName),
                viewIntent("vnd.youtube://results?search_query=$encoded", packageName),
            )
            "com.google.android.apps.youtube.music" -> listOf(
                viewIntent("https://music.youtube.com/search?q=$encoded", packageName),
                viewIntent("vnd.youtube.music://search?q=$encoded", packageName),
            )
            "com.deezer.android.app" -> listOf(
                viewIntent("deezer://www.deezer.com/search/$encoded", packageName),
                viewIntent("https://www.deezer.com/search/$encoded", packageName),
            )
            "com.soundcloud.android" -> listOf(
                viewIntent("soundcloud://search:$encoded", packageName),
                viewIntent("https://soundcloud.com/search?q=$encoded", packageName),
            )
            "com.amazon.mp3" -> listOf(
                viewIntent("https://music.amazon.com/search/$encoded", packageName),
            )
            "com.apple.android.music" -> listOf(
                viewIntent("https://music.apple.com/search?term=$encoded", packageName),
            )
            else -> emptyList()
        }
    }

    private fun viewIntent(uri: String, packageName: String): Intent {
        return Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            setPackage(packageName)
        }
    }
}
