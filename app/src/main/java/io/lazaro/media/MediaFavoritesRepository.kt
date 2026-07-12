package io.lazaro.media

import io.lazaro.memory.MemoryRepository
import io.lazaro.memory.entity.MemoryCategory
import javax.inject.Inject
import javax.inject.Singleton

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

    private fun favoriteKey(category: MediaCategory): String = "media_favorite_${category.id}"

    private fun encodeFavorite(packageName: String, label: String): String = "$packageName|$label"

    private fun parseFavorite(raw: String): MediaFavorite? {
        val parts = raw.split("|", limit = 2)
        if (parts.size != 2 || parts[0].isBlank()) return null
        return MediaFavorite(packageName = parts[0], label = parts[1])
    }
}
