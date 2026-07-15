package io.lazaro.memory

import io.lazaro.memory.entity.MemoryEntry
import io.lazaro.memory.entity.MemoryCategory

data class SavedPlace(
    val key: String,
    val displayName: String,
    val latitude: Double,
    val longitude: Double,
    val address: String? = null,
)

object SavedPlaceCodec {
    private const val SEPARATOR = "|"

    fun encode(latitude: Double, longitude: Double, address: String? = null): String {
        val coords = "${latitude},${longitude}"
        return if (address.isNullOrBlank()) coords else "$coords$SEPARATOR$address"
    }

    fun decode(value: String): Pair<Double, Double>? {
        val coords = value.substringBefore(SEPARATOR).trim()
        val parts = coords.split(",")
        if (parts.size != 2) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lng = parts[1].toDoubleOrNull() ?: return null
        return lat to lng
    }

    fun decodeAddress(value: String): String? {
        val idx = value.indexOf(SEPARATOR)
        if (idx < 0) return null
        return value.substring(idx + 1).trim().ifBlank { null }
    }

    fun fromEntry(entry: MemoryEntry): SavedPlace? {
        if (entry.category != MemoryCategory.PLACE) return null
        val coords = decode(entry.value) ?: return null
        val address = decodeAddress(entry.value) ?: entry.notes.ifBlank { null }
        val displayName = entry.aliases.split("|").firstOrNull { it.isNotBlank() }
            ?: entry.key.replace('_', ' ')
        return SavedPlace(
            key = entry.key,
            displayName = displayName,
            latitude = coords.first,
            longitude = coords.second,
            address = address,
        )
    }

    fun slugify(name: String): String {
        return java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .trim()
            .replace(Regex("\\s+"), "_")
            .ifBlank { "sitio" }
    }
}
