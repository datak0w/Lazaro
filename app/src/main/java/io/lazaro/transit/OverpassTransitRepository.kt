package io.lazaro.transit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

@Singleton
class OverpassTransitRepository @Inject constructor() {

    suspend fun findNearbyStops(
        latitude: Double,
        longitude: Double,
        mode: TransitMode,
        radiusMeters: Int = 900,
        limit: Int = 8,
    ): List<TransitStop> {
        return withContext(Dispatchers.IO) {
            val query = buildOverpassQuery(latitude, longitude, radiusMeters, mode)
            val response = postOverpass(query)
            parseElements(response, latitude, longitude, mode)
                .sortedBy { it.distanceMeters }
                .distinctBy { "${it.name}|${it.type}" }
                .take(limit)
        }
    }

    private fun buildOverpassQuery(lat: Double, lon: Double, radius: Int, mode: TransitMode): String {
        val filters = when (mode) {
            TransitMode.BUS -> listOf(
                """node["highway"="bus_stop"](around:$radius,$lat,$lon);""",
                """node["public_transport"="platform"]["bus"="yes"](around:$radius,$lat,$lon);""",
            )
            TransitMode.METRO -> listOf(
                """node["railway"="station"]["station"="subway"](around:${radius * 2},$lat,$lon);""",
                """node["station"="subway"](around:${radius * 2},$lat,$lon);""",
            )
            TransitMode.TRAIN -> listOf(
                """node["railway"="station"](around:${radius * 2},$lat,$lon);""",
                """node["railway"="halt"](around:${radius * 2},$lat,$lon);""",
            )
            TransitMode.TRAM -> listOf(
                """node["railway"="tram_stop"](around:$radius,$lat,$lon);""",
                """node["public_transport"="platform"]["tram"="yes"](around:$radius,$lat,$lon);""",
            )
            TransitMode.ANY -> listOf(
                """node["highway"="bus_stop"](around:$radius,$lat,$lon);""",
                """node["public_transport"="platform"](around:$radius,$lat,$lon);""",
                """node["railway"~"station|halt|tram_stop"](around:${radius * 2},$lat,$lon);""",
                """node["station"="subway"](around:${radius * 2},$lat,$lon);""",
            )
        }

        return """
            [out:json][timeout:25];
            (
              ${filters.joinToString("\n              ")}
            );
            out body 30;
        """.trimIndent()
    }

    private fun postOverpass(query: String): JSONObject {
        val connection = (URL("https://overpass-api.de/api/interpreter").openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        val body = "data=${URLEncoder.encode(query, Charsets.UTF_8.name())}"
        connection.outputStream.use { it.write(body.toByteArray()) }
        val text = connection.inputStream.bufferedReader().readText()
        return JSONObject(text)
    }

    private fun parseElements(
        json: JSONObject,
        userLat: Double,
        userLon: Double,
        requestedMode: TransitMode,
    ): List<TransitStop> {
        val elements = json.optJSONArray("elements") ?: return emptyList()
        return buildList {
            for (i in 0 until elements.length()) {
                val element = elements.getJSONObject(i)
                if (element.optString("type") != "node") continue
                val lat = element.optDouble("lat")
                val lon = element.optDouble("lon")
                if (lat == 0.0 && lon == 0.0) continue

                val tags = element.optJSONObject("tags") ?: continue
                val name = tags.optString("name")
                    .ifBlank { tags.optString("ref") }
                    .ifBlank { tags.optString("stop_name") }
                if (name.isBlank()) continue

                val mode = inferMode(tags, requestedMode)
                val distance = haversineMeters(userLat, userLon, lat, lon).toInt()
                val lines = listOf(
                    tags.optString("route_ref"),
                    tags.optString("network"),
                    tags.optString("operator"),
                ).filter { it.isNotBlank() }.joinToString(", ").ifBlank { null }

                add(
                    TransitStop(
                        name = name,
                        type = mode,
                        latitude = lat,
                        longitude = lon,
                        distanceMeters = distance,
                        lineInfo = lines,
                    ),
                )
            }
        }
    }

    private fun inferMode(tags: JSONObject, fallback: TransitMode): TransitMode {
        val highway = tags.optString("highway")
        val railway = tags.optString("railway")
        val station = tags.optString("station")
        return when {
            station == "subway" || tags.optString("subway") == "yes" -> TransitMode.METRO
            railway == "tram_stop" || tags.optString("tram") == "yes" -> TransitMode.TRAM
            railway == "station" || railway == "halt" -> TransitMode.TRAIN
            highway == "bus_stop" || tags.optString("bus") == "yes" -> TransitMode.BUS
            else -> fallback
        }
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
