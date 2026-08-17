package io.lazaro.location

import io.lazaro.memory.SavedPlaceRepository
import io.lazaro.navigation.NavigationBearing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

data class NearbyLandmark(
    val name: String,
    val distanceMeters: Int,
    val source: String,
)

object NearbyLandmarkSpeech {
    fun formatSpokenLine(landmarks: List<NearbyLandmark>): String? {
        if (landmarks.isEmpty()) return null
        val parts = landmarks.map { lm ->
            val rounded = roundDistance(lm.distanceMeters)
            "a unos $rounded metros de ${lm.name}"
        }
        return when (parts.size) {
            1 -> "Como referencia, estás ${parts[0]}."
            2 -> "Como referencia, estás ${parts[0]}, y ${parts[1]}."
            else -> "Como referencia: ${parts.dropLast(1).joinToString("; ")}, y ${parts.last()}."
        }
    }

    fun roundDistance(meters: Int): Int {
        return when {
            meters <= 15 -> 10
            meters <= 100 -> ((meters + 4) / 5) * 5
            meters <= 300 -> ((meters + 5) / 10) * 10
            else -> ((meters + 25) / 50) * 50
        }
    }
}

/**
 * Referencias cercanas para «dónde estoy»: sitios guardados + POIs OSM (Overpass).
 */
@Singleton
class NearbyLandmarkRepository @Inject constructor(
    private val savedPlaceRepository: SavedPlaceRepository,
) {
    suspend fun findNearby(
        latitude: Double,
        longitude: Double,
        radiusMeters: Double = DEFAULT_RADIUS_M,
        limit: Int = DEFAULT_LIMIT,
    ): List<NearbyLandmark> = coroutineScope {
        val savedDeferred = async {
            savedPlaceRepository.findNearby(latitude, longitude, radiusMeters).map { place ->
                val d = NavigationBearing.distanceMeters(
                    latitude, longitude, place.latitude, place.longitude,
                ).roundToInt().coerceAtLeast(5)
                NearbyLandmark(place.displayName, d, SOURCE_SAVED)
            }
        }
        val osmDeferred = async {
            withTimeoutOrNull(OVERPASS_TIMEOUT_MS) {
                fetchOsmLandmarks(latitude, longitude, radiusMeters.toInt())
            }.orEmpty()
        }

        val saved = savedDeferred.await()
        val osm = osmDeferred.await()
        mergeAndRank(saved, osm, limit)
    }

    fun formatSpokenLine(landmarks: List<NearbyLandmark>): String? =
        NearbyLandmarkSpeech.formatSpokenLine(landmarks)

    private fun mergeAndRank(
        saved: List<NearbyLandmark>,
        osm: List<NearbyLandmark>,
        limit: Int,
    ): List<NearbyLandmark> {
        val byNorm = linkedMapOf<String, NearbyLandmark>()
        // Preferir sitios guardados del usuario.
        for (lm in saved.sortedBy { it.distanceMeters }) {
            byNorm.putIfAbsent(normalize(lm.name), lm)
        }
        for (lm in osm.sortedBy { it.distanceMeters }) {
            byNorm.putIfAbsent(normalize(lm.name), lm)
        }
        return byNorm.values
            .sortedBy { it.distanceMeters }
            .take(limit)
    }

    private suspend fun fetchOsmLandmarks(
        lat: Double,
        lon: Double,
        radius: Int,
    ): List<NearbyLandmark> = withContext(Dispatchers.IO) {
        try {
            val query = """
                [out:json][timeout:8];
                (
                  node(around:$radius,$lat,$lon)["name"]["amenity"];
                  node(around:$radius,$lat,$lon)["name"]["shop"];
                  node(around:$radius,$lat,$lon)["name"]["tourism"];
                  node(around:$radius,$lat,$lon)["name"]["leisure"];
                  node(around:$radius,$lat,$lon)["name"]["historic"];
                  way(around:$radius,$lat,$lon)["name"]["amenity"];
                  way(around:$radius,$lat,$lon)["name"]["shop"];
                  way(around:$radius,$lat,$lon)["name"]["tourism"];
                  way(around:$radius,$lat,$lon)["name"]["leisure"];
                  way(around:$radius,$lat,$lon)["name"]["place"~"square|plaza"];
                );
                out center 25;
            """.trimIndent()
            val json = postOverpass(query)
            parseElements(json, lat, lon)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun postOverpass(query: String): JSONObject {
        val connection = (URL(OVERPASS_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 8_000
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("User-Agent", "LazaroAI/1.0 (accessibility; offline-friendly)")
        }
        try {
            val body = "data=${URLEncoder.encode(query, Charsets.UTF_8.name())}"
            connection.outputStream.use { it.write(body.toByteArray()) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) return JSONObject()
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseElements(
        json: JSONObject,
        userLat: Double,
        userLon: Double,
    ): List<NearbyLandmark> {
        val elements = json.optJSONArray("elements") ?: return emptyList()
        return buildList {
            for (i in 0 until elements.length()) {
                val el = elements.optJSONObject(i) ?: continue
                val tags = el.optJSONObject("tags") ?: continue
                val name = tags.optString("name").trim()
                if (name.isBlank() || name.length < 2) continue
                if (isBoringName(name)) continue

                val (plat, plon) = when (el.optString("type")) {
                    "node" -> el.optDouble("lat") to el.optDouble("lon")
                    "way", "relation" -> {
                        val center = el.optJSONObject("center") ?: continue
                        center.optDouble("lat") to center.optDouble("lon")
                    }
                    else -> continue
                }
                if (plat == 0.0 && plon == 0.0) continue

                val dist = NavigationBearing.distanceMeters(userLat, userLon, plat, plon)
                    .roundToInt()
                    .coerceAtLeast(5)
                val spoken = spokenName(name, tags)
                add(NearbyLandmark(spoken, dist, SOURCE_OSM))
            }
        }
    }

    private fun spokenName(name: String, tags: JSONObject): String {
        // Si el nombre ya incluye tipo («Bar Castillo»), no duplicar.
        val lower = name.lowercase()
        val amenity = tags.optString("amenity")
        val shop = tags.optString("shop")
        val leisure = tags.optString("leisure")
        val tourism = tags.optString("tourism")
        val prefix = when {
            amenity == "bar" || amenity == "pub" -> "Bar"
            amenity == "cafe" || amenity == "cafe_restaurant" -> "Café"
            amenity == "restaurant" -> "Restaurante"
            amenity == "pharmacy" -> "Farmacia"
            amenity == "place_of_worship" -> null
            amenity == "bank" -> "Banco"
            amenity == "townhall" -> "Ayuntamiento"
            shop.isNotBlank() && !lower.contains(shop) -> null
            leisure == "park" -> "Parque"
            tourism == "museum" -> "Museo"
            else -> null
        }
        if (prefix != null && !lower.startsWith(prefix.lowercase())) {
            return "$prefix $name"
        }
        return name
    }

    private fun isBoringName(name: String): Boolean {
        val n = name.lowercase().trim()
        return n in setOf("entrada", "acceso", "parking", "aparcamiento", "wc", "aseos") ||
            n.matches(Regex("""^\d+$"""))
    }

    private fun normalize(name: String): String {
        return java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("""^(el|la|los|las|bar|cafe|café|restaurante|farmacia|parque)\s+"""), "")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    companion object {
        private const val OVERPASS_URL = "https://overpass-api.de/api/interpreter"
        private const val OVERPASS_TIMEOUT_MS = 7_000L
        private const val DEFAULT_RADIUS_M = 180.0
        private const val DEFAULT_LIMIT = 3
        private const val SOURCE_SAVED = "saved"
        private const val SOURCE_OSM = "osm"
    }
}
