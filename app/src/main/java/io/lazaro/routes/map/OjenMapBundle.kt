package io.lazaro.routes.map

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

enum class SegmentTerrain {
    URBAN_SIDEWALK,
    RURAL_LANE,
    WOODED,
    UNKNOWN,
}

@Singleton
class OjenMapBundle @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var footways: List<OsmWay> = emptyList()
    private var loaded = false

    suspend fun ensureLoaded(): Boolean = withContext(Dispatchers.IO) {
        if (loaded) return@withContext true
        val cache = cacheFile()
        if (cache.exists()) {
            footways = parseBundle(cache.readText())
            loaded = footways.isNotEmpty()
            if (loaded) return@withContext true
        }
        val downloaded = downloadOverpass()
        if (downloaded.isNotBlank()) {
            cache.parentFile?.mkdirs()
            cache.writeText(downloaded)
            footways = parseBundle(downloaded)
            loaded = footways.isNotEmpty()
        }
        loaded
    }

    fun classifySegment(lat: Double, lng: Double): SegmentTerrain {
        if (!loaded) return SegmentTerrain.UNKNOWN
        val nearFootway = footways.any { way ->
            way.points.any { p -> haversineM(lat, lng, p.first, p.second) < 12.0 }
        }
        if (!nearFootway) {
            val wooded = footways.any { it.tags["natural"] == "wood" || it.tags["landuse"] == "forest" }
            return if (wooded) SegmentTerrain.WOODED else SegmentTerrain.RURAL_LANE
        }
        return SegmentTerrain.URBAN_SIDEWALK
    }

    fun segmentTypeKey(terrain: SegmentTerrain): String = when (terrain) {
        SegmentTerrain.URBAN_SIDEWALK -> "urban_sidewalk"
        SegmentTerrain.RURAL_LANE -> "rural_lane"
        SegmentTerrain.WOODED -> "wooded"
        SegmentTerrain.UNKNOWN -> "unknown"
    }

    fun isOnFootway(lat: Double, lng: Double): Boolean {
        if (!loaded) return true
        return footways.any { way ->
            way.tags["highway"] in FOOTWAY_TYPES &&
                way.points.any { p -> haversineM(lat, lng, p.first, p.second) < 15.0 }
        }
    }

    private suspend fun downloadOverpass(): String = withContext(Dispatchers.IO) {
        try {
            val query = """
                [out:json][timeout:25];
                (
                  way["highway"~"footway|path|track|pedestrian|steps"](${BBOX_SOUTH},${BBOX_WEST},${BBOX_NORTH},${BBOX_EAST});
                  way["landuse"="forest"](${BBOX_SOUTH},${BBOX_WEST},${BBOX_NORTH},${BBOX_EAST});
                  way["natural"="wood"](${BBOX_SOUTH},${BBOX_WEST},${BBOX_NORTH},${BBOX_EAST});
                );
                out geom;
            """.trimIndent()
            val url = URL("https://overpass-api.de/api/interpreter")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            conn.outputStream.use { it.write("data=${java.net.URLEncoder.encode(query, "UTF-8")}".toByteArray()) }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            body
        } catch (_: Exception) {
            ""
        }
    }

    private fun parseBundle(json: String): List<OsmWay> {
        return try {
            val root = JSONObject(json)
            val elements = root.getJSONArray("elements")
            buildList {
                for (i in 0 until elements.length()) {
                    val el = elements.getJSONObject(i)
                    if (el.optString("type") != "way") continue
                    val geom = el.optJSONArray("geometry") ?: continue
                    val points = buildList {
                        for (g in 0 until geom.length()) {
                            val node = geom.getJSONObject(g)
                            add(node.getDouble("lat") to node.getDouble("lon"))
                        }
                    }
                    val tags = mutableMapOf<String, String>()
                    val tagsObj = el.optJSONObject("tags")
                    if (tagsObj != null) {
                        tagsObj.keys().forEach { key ->
                            tags[key] = tagsObj.getString(key)
                        }
                    }
                    add(OsmWay(points, tags))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun cacheFile(): File = File(context.filesDir, "maps/ojen_footways.json")

    private fun haversineM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLng / 2) * kotlin.math.sin(dLng / 2)
        return r * 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    }

    companion object {
        private const val BBOX_SOUTH = 36.54
        private const val BBOX_WEST = -4.90
        private const val BBOX_NORTH = 36.60
        private const val BBOX_EAST = -4.82
        private val FOOTWAY_TYPES = setOf("footway", "path", "track", "pedestrian", "steps")
    }
}

private data class OsmWay(
    val points: List<Pair<Double, Double>>,
    val tags: Map<String, String>,
)
