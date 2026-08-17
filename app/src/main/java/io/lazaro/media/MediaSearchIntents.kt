package io.lazaro.media

import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore

object MediaSearchIntents {

    fun candidates(packageName: String, query: String): List<Intent> {
        val playQuery = playlistOrientedQuery(packageName, query)
        val bareQuery = query.trim()
        val playFromSearch = playFromSearchCandidates(packageName, playQuery, bareQuery)

        val searchOnly = when (packageName) {
            "com.spotify.music", "com.spotify.lite" -> listOf(
                // Abrir directamente resultados de playlists (mejor para autoplay)
                viewIntent(
                    "https://open.spotify.com/search/${Uri.encode(bareQuery)}/playlists",
                    packageName,
                ),
                viewIntent("spotify:search:${Uri.encode(playQuery)}", packageName),
                viewIntent(
                    "https://open.spotify.com/search/${Uri.encode(playQuery)}",
                    packageName,
                ),
            )
            "com.google.android.youtube" -> listOf(
                Intent(Intent.ACTION_SEARCH).apply {
                    setPackage(packageName)
                    putExtra(SearchManager.QUERY, playQuery)
                },
                viewIntent(
                    "https://www.youtube.com/results?search_query=${Uri.encode(playQuery)}",
                    packageName,
                ),
            )
            "com.google.android.apps.youtube.music" -> listOf(
                viewIntent(
                    "https://music.youtube.com/search?q=${Uri.encode(playQuery)}",
                    packageName,
                ),
            )
            "com.deezer.android.app" -> listOf(
                viewIntent(
                    "deezer://www.deezer.com/search/${Uri.encode(playQuery)}",
                    packageName,
                ),
            )
            "com.soundcloud.android" -> listOf(
                viewIntent("soundcloud://search:${Uri.encode(playQuery)}", packageName),
            )
            "com.amazon.mp3" -> listOf(
                viewIntent(
                    "https://music.amazon.com/search/${Uri.encode(playQuery)}",
                    packageName,
                ),
            )
            "com.apple.android.music" -> listOf(
                viewIntent(
                    "https://music.apple.com/search?term=${Uri.encode(playQuery)}",
                    packageName,
                ),
            )
            else -> emptyList()
        }

        // Play-from-search primero; si Spotify lo ignora, cae a búsqueda de playlists.
        return playFromSearch + searchOnly
    }

    fun playlistOrientedQuery(packageName: String, query: String): String {
        val q = query.trim()
        if (q.isBlank()) return q
        val lower = q.lowercase()
        if (lower.contains("playlist") || lower.contains("lista")) return q
        val isSpotifyOrYtm = packageName == "com.spotify.music" ||
            packageName == "com.spotify.lite" ||
            packageName == "com.google.android.apps.youtube.music"
        if (!isSpotifyOrYtm) return q
        if (lower.contains(" de ") || lower.contains(" by ")) return q
        return "$q playlist"
    }

    private fun playFromSearchCandidates(
        packageName: String,
        playQuery: String,
        bareQuery: String,
    ): List<Intent> {
        return when (packageName) {
            "com.spotify.music", "com.spotify.lite" -> listOf(
                // Sin forzar MainActivity: deja que Spotify elija el handler de play-from-search
                playFromSearchIntent(
                    packageName = packageName,
                    query = playQuery,
                    focus = MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE,
                    extraKey = MediaStore.EXTRA_MEDIA_PLAYLIST,
                    extraValue = bareQuery,
                ),
                playFromSearchIntent(
                    packageName = packageName,
                    query = bareQuery,
                    focus = MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE,
                    extraKey = MediaStore.EXTRA_MEDIA_PLAYLIST,
                    extraValue = bareQuery,
                ),
                playFromSearchIntent(
                    packageName = packageName,
                    query = playQuery,
                    focus = MediaStore.Audio.Genres.ENTRY_CONTENT_TYPE,
                    extraKey = MediaStore.EXTRA_MEDIA_GENRE,
                    extraValue = bareQuery,
                ),
                playFromSearchIntent(
                    packageName = packageName,
                    query = bareQuery,
                    focus = MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE,
                    extraKey = MediaStore.EXTRA_MEDIA_ARTIST,
                    extraValue = bareQuery,
                ),
                playFromSearchIntent(
                    packageName = packageName,
                    query = playQuery,
                    focus = "vnd.android.cursor.item/*",
                    extraKey = null,
                    extraValue = null,
                ),
            )
            "com.google.android.youtube" -> listOf(
                playFromSearchIntent(
                    packageName = packageName,
                    query = playQuery,
                    focus = "vnd.android.cursor.item/video",
                    extraKey = MediaStore.EXTRA_MEDIA_TITLE,
                    extraValue = playQuery,
                ),
            )
            "com.google.android.apps.youtube.music" -> listOf(
                playFromSearchIntent(
                    packageName = packageName,
                    query = playQuery,
                    focus = MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE,
                    extraKey = MediaStore.EXTRA_MEDIA_PLAYLIST,
                    extraValue = bareQuery,
                ),
                playFromSearchIntent(
                    packageName = packageName,
                    query = playQuery,
                    focus = MediaStore.Audio.Media.ENTRY_CONTENT_TYPE,
                    extraKey = MediaStore.EXTRA_MEDIA_TITLE,
                    extraValue = bareQuery,
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
        extraValue: String?,
    ): Intent {
        return Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            setPackage(packageName)
            putExtra(SearchManager.QUERY, query)
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, focus)
            if (extraKey != null && !extraValue.isNullOrBlank()) {
                putExtra(extraKey, extraValue)
            }
        }
    }

    private fun viewIntent(uri: String, packageName: String): Intent {
        return Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            setPackage(packageName)
        }
    }
}
