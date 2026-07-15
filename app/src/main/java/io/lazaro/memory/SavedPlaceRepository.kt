package io.lazaro.memory

import io.lazaro.memory.entity.MemoryCategory
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SavedPlaceRepository @Inject constructor(
    private val memoryRepository: MemoryRepository,
) {
    suspend fun savePlace(
        name: String,
        latitude: Double,
        longitude: Double,
        address: String? = null,
    ): SavedPlace {
        val key = SavedPlaceCodec.slugify(name)
        val value = SavedPlaceCodec.encode(latitude, longitude, address)
        memoryRepository.saveMemory(
            key = key,
            value = value,
            category = MemoryCategory.PLACE,
            aliases = listOf(name.trim(), key),
            notes = address.orEmpty(),
            source = "saved_place",
        )
        return SavedPlace(
            key = key,
            displayName = name.trim(),
            latitude = latitude,
            longitude = longitude,
            address = address,
        )
    }

    suspend fun resolvePlace(keyOrAlias: String): SavedPlace? {
        val normalized = keyOrAlias.trim()
        if (normalized.isBlank()) return null

        memoryRepository.getAllMemories()
            .filter { it.category == MemoryCategory.PLACE }
            .forEach { entry ->
                SavedPlaceCodec.fromEntry(entry)?.let { place ->
                    if (matches(place, normalized)) return place
                }
            }
        return null
    }

    suspend fun getAllPlaces(): List<SavedPlace> {
        return memoryRepository.getAllMemories()
            .filter { it.category == MemoryCategory.PLACE }
            .mapNotNull { SavedPlaceCodec.fromEntry(it) }
            .sortedBy { it.displayName.lowercase() }
    }

    suspend fun deletePlace(keyOrAlias: String): Boolean {
        val place = resolvePlace(keyOrAlias) ?: return false
        memoryRepository.deleteMemory(place.key)
        return true
    }

    private fun matches(place: SavedPlace, query: String): Boolean {
        val q = stripArticles(query.lowercase().trim())
        val names = listOf(
            place.key.replace('_', ' '),
            place.displayName.lowercase(),
        )
        return names.any { name ->
            val n = stripArticles(name)
            n == q || n.contains(q) || q.contains(n)
        }
    }

    private fun stripArticles(text: String): String {
        return text
            .replace(Regex("""^(la|el|las|los|mi|mis)\s+"""), "")
            .trim()
    }
}
