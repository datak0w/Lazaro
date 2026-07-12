package io.lazaro.audiobook

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GutenbergRepository @Inject constructor() {

    suspend fun searchSpanish(query: String? = null, limit: Int = 5): List<AudiobookOffer> {
        return withContext(Dispatchers.IO) {
            val encodedQuery = query?.trim()?.takeIf { it.isNotBlank() }
                ?.let { URLEncoder.encode(it, Charsets.UTF_8.name()) }
            val url = buildString {
                append("https://gutendex.com/books/?languages=es")
                if (encodedQuery != null) append("&search=$encodedQuery")
            }
            fetchBooks(url, limit)
        }
    }

    suspend fun fetchText(url: String, maxChars: Int = 12000): String {
        return withContext(Dispatchers.IO) {
            val raw = downloadText(url)
            cleanGutenbergText(raw).take(maxChars)
        }
    }

    private fun fetchBooks(url: String, limit: Int): List<AudiobookOffer> {
        val json = JSONObject(downloadText(url))
        val results = json.optJSONArray("results") ?: return emptyList()

        return buildList {
            for (i in 0 until minOf(results.length(), limit)) {
                val item = results.getJSONObject(i)
                parseBook(item)?.let { add(it) }
            }
        }
    }

    private fun parseBook(item: JSONObject): AudiobookOffer? {
        val id = item.optInt("id", -1)
        if (id < 0) return null

        val title = item.optString("title").ifBlank { return null }
        val authors = item.optJSONArray("authors")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.optString("name")?.takeIf { it.isNotBlank() }
            }.joinToString(", ")
        }.orEmpty().ifBlank { "Autor desconocido" }

        val formats = item.optJSONObject("formats") ?: return null
        val textUrl = formats.optString("text/plain; charset=utf-8").ifBlank {
            formats.optString("text/plain; charset=us-ascii")
        }.ifBlank { null }

        val audioUrl = formats.optString("audio/mpeg").ifBlank {
            formats.optString("audio/mp4")
        }.ifBlank {
            formats.optString("audio/ogg")
        }.ifBlank { null }

        if (textUrl == null && audioUrl == null) return null

        return AudiobookOffer(
            id = "gutenberg:$id",
            title = title,
            authors = authors,
            source = AudiobookSource.GUTENBERG,
            textUrl = textUrl,
            audioUrl = audioUrl,
            rssUrl = null,
        )
    }

    private fun downloadText(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = "GET"
        }
        connection.inputStream.bufferedReader().use { return it.readText() }
    }

    private fun cleanGutenbergText(raw: String): String {
        var text = raw
        val startMarkers = listOf("*** START", "*** COMIENZO", "*** START OF")
        val endMarkers = listOf("*** END", "*** FIN", "*** END OF")

        for (marker in startMarkers) {
            val index = text.indexOf(marker, ignoreCase = true)
            if (index >= 0) {
                text = text.substring(index)
                val lineBreak = text.indexOf('\n')
                if (lineBreak >= 0) text = text.substring(lineBreak + 1)
                break
            }
        }

        for (marker in endMarkers) {
            val index = text.indexOf(marker, ignoreCase = true)
            if (index >= 0) {
                text = text.substring(0, index)
                break
            }
        }

        return text
            .replace(Regex("\\r\\n"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }
}
