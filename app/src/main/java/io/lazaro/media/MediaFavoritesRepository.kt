package io.lazaro.media

import io.lazaro.memory.MemoryRepository
import io.lazaro.memory.entity.MemoryCategory
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class RecentMediaPlay(
    val query: String,
    val packageName: String,
    val label: String,
)

@Singleton
class MediaFavoritesRepository @Inject constructor(
    private val memoryRepository: MemoryRepository,
) {
    suspend fun getFavorite(category: MediaCategory): MediaFavorite? {
        val raw = memoryRepository.getMemory(favoriteKey(category))?.value ?: return null
        return parseFavorite(raw)
    }

    suspend fun saveFavorite(category: MediaCategory, app: InstalledMediaApp) {
        memoryRepository.saveMemory(
            key = favoriteKey(category),
            value = encodeFavorite(app.packageName, app.label),
            category = MemoryCategory.PREFERENCE,
            aliases = listOf(category.spokenLabel, "favorito ${category.spokenLabel}"),
            notes = "App favorita para ${category.spokenLabel}",
            source = "media_launcher",
        )
    }

    suspend fun getRecentPlays(limit: Int = 5): List<RecentMediaPlay> {
        val raw = memoryRepository.getMemory(RECENT_PLAYS_KEY)?.value ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val query = obj.optString("query").trim()
                    val pkg = obj.optString("package").trim()
                    val label = obj.optString("label").trim()
                    if (query.isNotBlank() && pkg.isNotBlank()) {
                        add(RecentMediaPlay(query, pkg, label.ifBlank { pkg }))
                    }
                }
            }.take(limit)
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun rememberPlay(query: String, packageName: String, label: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank() || packageName.isBlank()) return
        val current = getRecentPlays(MAX_RECENT).toMutableList()
        current.removeAll {
            it.query.equals(trimmed, ignoreCase = true) &&
                it.packageName == packageName
        }
        current.add(0, RecentMediaPlay(trimmed, packageName, label.ifBlank { packageName }))
        val trimmedList = current.take(MAX_RECENT)
        val arr = JSONArray()
        for (item in trimmedList) {
            arr.put(
                JSONObject()
                    .put("query", item.query)
                    .put("package", item.packageName)
                    .put("label", item.label),
            )
        }
        memoryRepository.saveMemory(
            key = RECENT_PLAYS_KEY,
            value = arr.toString(),
            category = MemoryCategory.PREFERENCE,
            aliases = listOf("musica reciente", "últimas canciones", "historial musica"),
            notes = "Historial de música pedida por voz",
            source = "media_launcher",
        )
    }

    private fun favoriteKey(category: MediaCategory): String = "media_favorite_${category.id}"

    private fun encodeFavorite(packageName: String, label: String): String = "$packageName|$label"

    private fun parseFavorite(raw: String): MediaFavorite? {
        val parts = raw.split("|", limit = 2)
        if (parts.size != 2 || parts[0].isBlank()) return null
        return MediaFavorite(packageName = parts[0], label = parts[1])
    }

    companion object {
        private const val RECENT_PLAYS_KEY = "media_recent_plays"
        private const val MAX_RECENT = 8
    }
}
