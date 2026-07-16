package io.lazaro.routes.map

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Carga el corredor ODM pueblo→casa (GeoJSON + perfil JSON) desde assets o filesDir.
 *
 * Tras procesar GoPro+ODM en PC, copiar a:
 * `filesDir/maps/ojen_odm/corridor_path.geojson`
 * `filesDir/maps/ojen_odm/corridor_profile.json`
 * `filesDir/maps/ojen_odm/corridor_nodes.json` (opcional)
 */
@Singleton
class OjenOdmBundle @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var bundle: CorridorBundle = CorridorBundle("", emptyList(), emptyList(), emptyList())
    private var loaded = false

    suspend fun ensureLoaded(): Boolean = withContext(Dispatchers.IO) {
        if (loaded && !bundle.isEmpty) return@withContext true
        val fromFiles = loadFromDir(File(context.filesDir, "maps/ojen_odm"))
        if (fromFiles != null) {
            bundle = fromFiles
            loaded = true
            return@withContext true
        }
        val fromAssets = loadFromAssets()
        if (fromAssets != null) {
            bundle = fromAssets
            loaded = true
            return@withContext true
        }
        loaded = false
        false
    }

    fun isLoaded(): Boolean = loaded && !bundle.isEmpty

    fun currentBundle(): CorridorBundle = bundle

    fun snap(lat: Double, lng: Double, accuracyM: Float = 10f): CorridorSnapResult? {
        if (!isLoaded()) return null
        return CorridorSnapEngine.snap(lat, lng, bundle, accuracyM)
    }

    fun profileAt(alongM: Float): CorridorProfilePoint? {
        if (!isLoaded()) return null
        return CorridorSnapEngine.profileAt(bundle, alongM)
    }

    fun nodeAhead(alongM: Float, lookaheadM: Float = 25f): CorridorNode? {
        if (!isLoaded()) return null
        return CorridorSnapEngine.nodeAhead(bundle, alongM, lookaheadM)
    }

    fun classifySegment(lat: Double, lng: Double): SegmentTerrain {
        val snap = snap(lat, lng) ?: return SegmentTerrain.UNKNOWN
        if (!snap.onCorridor) return SegmentTerrain.UNKNOWN
        return when (snap.segmentTag.lowercase()) {
            "urban", "urban_sidewalk", "pueblo" -> SegmentTerrain.URBAN_SIDEWALK
            "wooded", "forest", "arbolado" -> SegmentTerrain.WOODED
            "rural", "rural_lane", "campo", "track" -> SegmentTerrain.RURAL_LANE
            else -> SegmentTerrain.RURAL_LANE
        }
    }

    fun segmentTypeKey(lat: Double, lng: Double): String? {
        val snap = snap(lat, lng) ?: return null
        if (!snap.onCorridor) return null
        return snap.segmentTag.ifBlank { "odm_corridor" }
    }

    /** Perfil canónico semilla para crear ruta "casa" antes de grabaciones. */
    fun seedProfilePoints(): List<CorridorProfilePoint> = bundle.profile

    private fun loadFromDir(dir: File): CorridorBundle? {
        val pathFile = File(dir, "corridor_path.geojson")
        val profileFile = File(dir, "corridor_profile.json")
        if (!pathFile.exists() || !profileFile.exists()) return null
        return parseBundle(
            name = dir.name,
            pathJson = pathFile.readText(),
            profileJson = profileFile.readText(),
            nodesJson = File(dir, "corridor_nodes.json").takeIf { it.exists() }?.readText(),
        )
    }

    private fun loadFromAssets(): CorridorBundle? {
        return try {
            val pathJson = context.assets.open("maps/ojen_odm/corridor_path.geojson")
                .bufferedReader().use { it.readText() }
            val profileJson = context.assets.open("maps/ojen_odm/corridor_profile.json")
                .bufferedReader().use { it.readText() }
            val nodesJson = try {
                context.assets.open("maps/ojen_odm/corridor_nodes.json")
                    .bufferedReader().use { it.readText() }
            } catch (_: Exception) {
                null
            }
            parseBundle("ojen_odm", pathJson, profileJson, nodesJson)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        fun parseBundle(
            name: String,
            pathJson: String,
            profileJson: String,
            nodesJson: String?,
        ): CorridorBundle? {
            val path = parsePathGeoJson(pathJson)
            val profile = parseProfileJson(profileJson)
            val nodes = nodesJson?.let { parseNodesJson(it) } ?: emptyList()
            if (path.size < 2) return null
            return CorridorBundle(name, path, profile, nodes)
        }

        fun parsePathGeoJson(json: String): List<CorridorPathPoint> {
            return try {
                val root = JSONObject(json)
                val coords = when (root.optString("type")) {
                    "Feature" -> root.getJSONObject("geometry").getJSONArray("coordinates")
                    "FeatureCollection" -> {
                        val features = root.getJSONArray("features")
                        if (features.length() == 0) return emptyList()
                        features.getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates")
                    }
                    else -> root.optJSONArray("coordinates") ?: return emptyList()
                }
                buildList {
                    for (i in 0 until coords.length()) {
                        val c = coords.getJSONArray(i)
                        add(CorridorPathPoint(lat = c.getDouble(1), lng = c.getDouble(0)))
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun parseProfileJson(json: String): List<CorridorProfilePoint> {
            return try {
                val arr = JSONArray(json)
                buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        add(
                            CorridorProfilePoint(
                                lat = o.getDouble("lat"),
                                lng = o.getDouble("lng"),
                                distanceAlongM = o.getDouble("alongM").toFloat(),
                                bearingDeg = o.optDouble("bearingDeg", 0.0).toFloat(),
                                gradePct = o.optDouble("gradePct", 0.0).toFloat(),
                                widthM = o.optDouble("widthM", 2.5).toFloat(),
                                segmentTag = o.optString("segmentTag", "rural_lane"),
                            ),
                        )
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun parseNodesJson(json: String): List<CorridorNode> {
            return try {
                val arr = JSONArray(json)
                buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        add(
                            CorridorNode(
                                lat = o.getDouble("lat"),
                                lng = o.getDouble("lng"),
                                distanceAlongM = o.getDouble("alongM").toFloat(),
                                type = parseNodeType(o.optString("type", "other")),
                                label = o.optString("label", ""),
                            ),
                        )
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        private fun parseNodeType(raw: String): CorridorNodeType = when (raw.lowercase()) {
            "fork", "bifurcacion" -> CorridorNodeType.FORK
            "gate", "verja", "cancela" -> CorridorNodeType.GATE
            "road_cross", "cruce" -> CorridorNodeType.ROAD_CROSS
            "destination", "casa", "destino" -> CorridorNodeType.DESTINATION
            else -> CorridorNodeType.OTHER
        }
    }
}
