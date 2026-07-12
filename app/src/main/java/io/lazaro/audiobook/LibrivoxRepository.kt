package io.lazaro.audiobook

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LibrivoxRepository @Inject constructor() {

    suspend fun searchSpanish(query: String? = null, limit: Int = 5): List<AudiobookOffer> {
        return withContext(Dispatchers.IO) {
            val books = fetchCatalog(limit = 80)
            val filtered = books.filter { book ->
                val isSpanish = book.language.contains("spanish", ignoreCase = true) ||
                    book.language.contains("español", ignoreCase = true)
                if (query.isNullOrBlank()) {
                    isSpanish
                } else {
                    val q = query.lowercase()
                    isSpanish && (
                        book.title.lowercase().contains(q) ||
                            book.authors.lowercase().contains(q) ||
                            q.split(" ").any { word -> word.length > 2 && book.title.lowercase().contains(word) }
                        )
                }
            }
            when {
                filtered.isNotEmpty() -> filtered.take(limit).map { it.toOffer() }
                !query.isNullOrBlank() -> books.filter { book ->
                    val q = query.lowercase()
                    book.title.lowercase().contains(q) || book.authors.lowercase().contains(q)
                }.take(limit).map { it.toOffer() }
                else -> books.filter { it.language.contains("spanish", ignoreCase = true) }
                    .take(limit).map { it.toOffer() }
            }
        }
    }

    suspend fun resolveAudioUrl(offer: AudiobookOffer): String? {
        offer.audioUrl?.let { return it }
        val rss = offer.rssUrl ?: return null
        return withContext(Dispatchers.IO) { parseFirstMp3FromRss(rss) }
    }

    private fun fetchCatalog(limit: Int): List<LibrivoxEntry> {
        val url = "https://librivox.org/api/feed/audiobooks?format=json&limit=$limit"
        val json = JSONObject(downloadText(url))
        val books = json.optJSONArray("books") ?: return emptyList()
        return buildList {
            for (i in 0 until books.length()) {
                val item = books.getJSONObject(i)
                val id = item.optInt("id", -1)
                if (id < 0) continue
                val title = item.optString("title")
                if (title.isBlank()) continue
                val authors = item.optJSONArray("authors")?.let { array ->
                    (0 until array.length()).mapNotNull { index ->
                        array.optJSONObject(index)?.let { author ->
                            listOf(
                                author.optString("first_name"),
                                author.optString("last_name"),
                            ).filter { it.isNotBlank() }.joinToString(" ")
                        }
                    }.joinToString(", ")
                }.orEmpty().ifBlank { "Autor desconocido" }
                add(
                    LibrivoxEntry(
                        id = id,
                        title = title,
                        authors = authors,
                        language = item.optString("language"),
                        rssUrl = item.optString("url_rss"),
                    ),
                )
            }
        }
    }

    private fun parseFirstMp3FromRss(rssUrl: String): String? {
        val xml = downloadText(rssUrl)
        val enclosureRegex = Regex("""<enclosure[^>]+url="([^"]+\.mp3[^"]*)"""", RegexOption.IGNORE_CASE)
        return enclosureRegex.find(xml)?.groupValues?.getOrNull(1)
    }

    private fun downloadText(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = "GET"
        }
        connection.inputStream.bufferedReader().use { return it.readText() }
    }

    private data class LibrivoxEntry(
        val id: Int,
        val title: String,
        val authors: String,
        val language: String,
        val rssUrl: String,
    ) {
        fun toOffer() = AudiobookOffer(
            id = "librivox:$id",
            title = title,
            authors = authors,
            source = AudiobookSource.LIBRIVOX,
            textUrl = null,
            audioUrl = null,
            rssUrl = rssUrl.ifBlank { null },
        )
    }
}

@Singleton
class LibbyHelper @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun isInstalled(): Boolean {
        return context.packageManager.getLaunchIntentForPackage(LIBBY_PACKAGE) != null
    }

    fun openApp(): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(LIBBY_PACKAGE) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }

    fun hint(): String? {
        return if (isInstalled()) {
            "También tienes Libby: audiolibros gratis con carnet de biblioteca."
        } else {
            null
        }
    }

    companion object {
        const val LIBBY_PACKAGE = "com.overdrive.mobile.android.libby"
    }
}
