package io.lazaro.navigation

import io.lazaro.memory.MemoryRepository
import io.lazaro.memory.entity.MemoryCategory
import io.lazaro.memory.entity.MemoryEntry
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

enum class PreferredSidewalkSide {
    LEFT,
    RIGHT,
    EITHER,
}

data class StreetSidePreference(
    val streetKey: String,
    val preferredSide: PreferredSidewalkSide,
    val reason: String? = null,
)

/**
 * Preferencias de acera por calle (memoria local, sin migración Room).
 * Clave: street_side:<nombre normalizado>
 */
@Singleton
class StreetSidePreferenceRepository @Inject constructor(
    private val memoryRepository: MemoryRepository,
) {
    private val cache = ConcurrentHashMap<String, StreetSidePreference>()

    suspend fun save(
        streetName: String,
        side: PreferredSidewalkSide,
        reason: String? = null,
    ): StreetSidePreference {
        val notes = reason?.trim().orEmpty()
        memoryRepository.saveMemory(
            key = memoryKey(streetName),
            value = side.name,
            category = MemoryCategory.STREET_SIDE,
            aliases = listOf(normalizeStreet(streetName)),
            notes = notes,
            source = "user_said",
        )
        val pref = StreetSidePreference(normalizeStreet(streetName), side, reason)
        cache[pref.streetKey] = pref
        return pref
    }

    suspend fun findForStreet(streetName: String?): StreetSidePreference? {
        if (streetName.isNullOrBlank()) return null
        val normalized = normalizeStreet(streetName)
        if (normalized.isBlank()) return null
        cache[normalized]?.let { return it }
        memoryRepository.getMemory(memoryKey(streetName))?.let {
            val pref = fromEntry(it)
            cache[pref.streetKey] = pref
            return pref
        }
        val all = memoryRepository.getAllMemories()
            .filter { it.category == MemoryCategory.STREET_SIDE }
        return all.firstOrNull { entry ->
            val alias = entry.aliases.split("|").firstOrNull { it.isNotBlank() }
                ?: entry.key.removePrefix(KEY_PREFIX)
            alias.contains(normalized) || normalized.contains(alias)
        }?.let {
            val pref = fromEntry(it)
            cache[pref.streetKey] = pref
            pref
        }
    }

    /** Lectura síncrona desde caché (pitidos). */
    fun cachedPreference(streetName: String?): StreetSidePreference? {
        if (streetName.isNullOrBlank()) return null
        val normalized = normalizeStreet(streetName)
        return cache[normalized] ?: cache.entries.firstOrNull { (k, _) ->
            k.contains(normalized) || normalized.contains(k)
        }?.value
    }

    suspend fun hintLineForStreet(streetName: String?): String? {
        val pref = findForStreet(streetName) ?: return null
        if (pref.preferredSide == PreferredSidewalkSide.EITHER) return null
        val sideWord = when (pref.preferredSide) {
            PreferredSidewalkSide.LEFT -> "izquierda"
            PreferredSidewalkSide.RIGHT -> "derecha"
            PreferredSidewalkSide.EITHER -> return null
        }
        val reason = pref.reason?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        return "En ${pref.streetKey}: acera de la $sideWord$reason."
    }

    private fun fromEntry(entry: MemoryEntry): StreetSidePreference {
        val side = runCatching {
            PreferredSidewalkSide.valueOf(entry.value.uppercase())
        }.getOrDefault(PreferredSidewalkSide.EITHER)
        val street = entry.aliases.split("|").firstOrNull { it.isNotBlank() }
            ?: entry.key.removePrefix(KEY_PREFIX)
        return StreetSidePreference(
            streetKey = street,
            preferredSide = side,
            reason = entry.notes.ifBlank { null },
        )
    }

    companion object {
        private const val KEY_PREFIX = "street_side:"

        fun memoryKey(streetName: String): String = KEY_PREFIX + normalizeStreet(streetName)

        fun normalizeStreet(raw: String): String {
            return raw.lowercase()
                .replace(Regex("""^(calle|avenida|avda\.?|av\.|camino|plaza|paseo)\s+"""), "")
                .replace(Regex("""[^\p{L}\p{N}\s]"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
        }
    }
}
