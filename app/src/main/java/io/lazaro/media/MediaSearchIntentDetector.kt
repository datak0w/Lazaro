package io.lazaro.media

import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

data class MediaSearchRequest(
    val query: String,
    val appAlias: String? = null,
)

@Singleton
class MediaSearchIntentDetector @Inject constructor() {

    fun detect(userText: String): MediaSearchRequest? {
        val text = normalize(userText)
        if (text.isBlank()) return null

        val appAliases = allSearchAliases().sortedByDescending { it.length }
        val matchedApp = appAliases.firstOrNull { alias ->
            containsAppAlias(text, alias)
        }

        parseQueryInApp(text, matchedApp)?.let { query ->
            if (isValidQuery(query)) {
                return MediaSearchRequest(query = query, appAlias = matchedApp)
            }
        }

        parseAppFirst(text, appAliases)?.let { return it }

        parseBareSearch(text)?.let { return it }

        return null
    }

    private fun parseQueryInApp(text: String, appAlias: String?): String? {
        if (appAlias == null) return null

        val pattern = Regex("""(.+?)\s+en\s+${Regex.escape(appAlias)}\s*$""")
        val match = pattern.find(text) ?: return null

        val query = stripLeadingVerbs(match.groupValues[1].trim())
        if (!isValidQuery(query)) return null

        return query
    }

    private fun parseAppFirst(text: String, appAliases: List<String>): MediaSearchRequest? {
        for (alias in appAliases) {
            val pattern = Regex("""^(?:${VERB_PREFIX})?\s*${Regex.escape(alias)}\s+(.+)$""")
            val match = pattern.find(text) ?: continue

            val query = stripLeadingVerbs(match.groupValues[1].trim())
            if (!isValidQuery(query)) continue

            return MediaSearchRequest(query = query, appAlias = alias)
        }
        return null
    }

    private fun parseBareSearch(text: String): MediaSearchRequest? {
        if (!SEARCH_VERBS.any { text.startsWith("$it ") || text == it }) return null

        val query = stripLeadingVerbs(text)
        if (!isValidQuery(query)) return null

        return MediaSearchRequest(query = query, appAlias = null)
    }

    private fun containsAppAlias(text: String, alias: String): Boolean {
        return Regex("""\ben\s+${Regex.escape(alias)}\b""").containsMatchIn(text) ||
            Regex("""\b${Regex.escape(alias)}\s+\S""").containsMatchIn(text)
    }

    private fun stripLeadingVerbs(text: String): String {
        var result = text.trim()
        var changed = true
        while (changed) {
            changed = false
            for (verb in SEARCH_VERBS) {
                if (result == verb) return ""
                if (result.startsWith("$verb ")) {
                    result = result.removePrefix("$verb ").trim()
                    changed = true
                    break
                }
            }
        }
        return result
            .replace(Regex("""\b(la|el|las|los|un|una|de|del)\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun isValidQuery(query: String): Boolean {
        if (query.length < 2) return false
        val normalized = query.lowercase().trim()
        if (normalized in CATEGORY_ONLY) return false
        if (SEARCH_VERBS.contains(normalized)) return false
        return true
    }

    private fun allSearchAliases(): List<String> {
        return MediaAppCatalog.knownApps
            .flatMap { it.searchAliases }
            .distinct()
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
        private val SEARCH_VERBS = listOf(
            "pon", "ponme", "reproduce", "reproducir", "busca", "buscar",
            "escucha", "escuchar", "quiero escuchar", "quiero oir", "quiero ver",
            "abre", "abrir", "toca", "tocar", "dale", "suena",
        )
        private val VERB_PREFIX = SEARCH_VERBS.joinToString("|") { Regex.escape(it) }
        private val CATEGORY_ONLY = setOf(
            "musica", "radio", "noticias", "podcast", "podcasts", "video", "videos",
            "spotify", "youtube", "cancion", "canciones",
        )
    }
}
