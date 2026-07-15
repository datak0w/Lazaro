package io.lazaro.media

import android.app.SearchManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore

object MediaSearchIntents {

    fun candidates(packageName: String, query: String): List<Intent> {
        val encoded = Uri.encode(query)
        val playFromSearch = playFromSearchCandidates(packageName, query)

        val searchOnly = when (packageName) {
            "com.spotify.music", "com.spotify.lite" -> listOf(
                viewIntent("spotify:search:$query", packageName),
                viewIntent("https://open.spotify.com/search/$encoded", packageName),
            )
            "com.google.android.youtube" -> listOf(
                Intent(Intent.ACTION_SEARCH).apply {
                    setPackage(packageName)
                    putExtra(SearchManager.QUERY, query)
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

        return playFromSearch + searchOnly
    }

    private fun playFromSearchCandidates(packageName: String, query: String): List<Intent> {
        return when (packageName) {
            "com.spotify.music", "com.spotify.lite" -> listOf(
                playFromSearchIntent(
                    packageName = packageName,
                    query = query,
                    focus = MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE,
                    extraKey = MediaStore.EXTRA_MEDIA_ARTIST,
                    useMainComponent = packageName == "com.spotify.music",
                ),
                playFromSearchIntent(
                    packageName = packageName,
                    query = query,
                    focus = MediaStore.Audio.Media.ENTRY_CONTENT_TYPE,
                    extraKey = MediaStore.EXTRA_MEDIA_TITLE,
                    useMainComponent = packageName == "com.spotify.music",
                ),
                playFromSearchIntent(
                    packageName = packageName,
                    query = query,
                    focus = "vnd.android.cursor.item/*",
                    extraKey = null,
                    useMainComponent = packageName == "com.spotify.music",
                ),
            )
            "com.google.android.youtube" -> listOf(
                playFromSearchIntent(
                    packageName = packageName,
                    query = query,
                    focus = "vnd.android.cursor.item/video",
                    extraKey = MediaStore.EXTRA_MEDIA_TITLE,
                    useMainComponent = false,
                ),
                playFromSearchIntent(
                    packageName = packageName,
                    query = query,
                    focus = "vnd.android.cursor.item/*",
                    extraKey = null,
                    useMainComponent = false,
                ),
            )
            "com.google.android.apps.youtube.music" -> listOf(
                playFromSearchIntent(
                    packageName = packageName,
                    query = query,
                    focus = MediaStore.Audio.Media.ENTRY_CONTENT_TYPE,
                    extraKey = MediaStore.EXTRA_MEDIA_TITLE,
                    useMainComponent = false,
                ),
            )
            else -> emptyList()
        }
    }

    private fun playFromSearchIntent(
        packageName: String,
        query: String,
        focus: String,
        extraKey: String?,
        useMainComponent: Boolean,
    ): Intent {
        return Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            setPackage(packageName)
            if (useMainComponent) {
                component = ComponentName(packageName, "$packageName.MainActivity")
            }
            putExtra(SearchManager.QUERY, query)
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, focus)
            if (extraKey != null) {
                putExtra(extraKey, query)
            }
        }
    }

    private fun viewIntent(uri: String, packageName: String): Intent {
        return Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            setPackage(packageName)
        }
    }
}
