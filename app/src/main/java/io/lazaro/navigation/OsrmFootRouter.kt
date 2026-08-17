package io.lazaro.navigation

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

data class OsrmLatLng(
    val lat: Double,
    val lng: Double,
)

data class OsrmStep(
    val maneuverType: String,
    val modifier: String?,
    val bearingAfter: Float?,
    val distanceM: Double,
    val durationS: Double,
    val name: String?,
    val locationLat: Double?,
    val locationLng: Double?,
    /** Distancia acumulada desde el origen hasta el inicio de este step. */
    val startAlongM: Double,
    /** Geometría del tramo (puede estar vacía). */
    val geometry: List<OsrmLatLng> = emptyList(),
) {
    val endAlongM: Double get() = startAlongM + distanceM
}

data class OsrmRoutePlan(
    val polyline: List<OsrmLatLng>,
    val totalDistanceM: Double,
    val totalDurationS: Double,
    val steps: List<OsrmStep>,
)

@Singleton
class OsrmFootRouter @Inject constructor() {

    fun fetchRoutePlan(
        originLat: Double,
        originLng: Double,
        destLat: Double,
        destLng: Double,
    ): OsrmRoutePlan? {
        val url =
            "https://router.project-osrm.org/route/v1/foot/" +
                "$originLng,$originLat;$destLng,$destLat" +
                "?steps=true&overview=full&geometries=geojson&annotations=true"
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6_000
                readTimeout = 10_000
                requestMethod = "GET"
            }
            try {
                if (conn.responseCode !in 200..299) return null
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                parseRoutePlan(body)
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Compatibilidad: solo steps (sin polilínea). */
    fun fetchSteps(
        originLat: Double,
        originLng: Double,
        destLat: Double,
        destLng: Double,
    ): List<OsrmStep>? = fetchRoutePlan(originLat, originLng, destLat, destLng)?.steps

    fun parseRoutePlan(json: String): OsrmRoutePlan? {
        return try {
            val root = JSONObject(json)
            if (root.optString("code") != "Ok") return null
            val routes = root.optJSONArray("routes") ?: return null
            if (routes.length() == 0) return null
            val route = routes.getJSONObject(0)
            val totalDistanceM = route.optDouble("distance", 0.0)
            val totalDurationS = route.optDouble("duration", 0.0)
            val polyline = parseGeoJsonLine(route.optJSONObject("geometry"))
            val legs = route.optJSONArray("legs") ?: return null
            if (legs.length() == 0) return null
            val stepsArr = legs.getJSONObject(0).optJSONArray("steps") ?: return null
            var along = 0.0
            val steps = buildList {
                for (i in 0 until stepsArr.length()) {
                    val step = stepsArr.getJSONObject(i)
                    val man = step.optJSONObject("maneuver") ?: continue
                    val loc = man.optJSONArray("location")
                    val dist = step.optDouble("distance", 0.0)
                    val name = step.optString("name").ifBlank { null }
                    add(
                        OsrmStep(
                            maneuverType = man.optString("type", "turn"),
                            modifier = man.optString("modifier").ifBlank { null },
                            bearingAfter = if (man.has("bearing_after")) {
                                man.optDouble("bearing_after").toFloat()
                            } else {
                                null
                            },
                            distanceM = dist,
                            durationS = step.optDouble("duration", 0.0),
                            name = name,
                            locationLat = loc?.optDouble(1),
                            locationLng = loc?.optDouble(0),
                            startAlongM = along,
                            geometry = parseGeoJsonLine(step.optJSONObject("geometry")),
                        ),
                    )
                    along += dist
                }
            }
            if (steps.isEmpty()) return null
            val poly = polyline.ifEmpty {
                // Fallback: concatenar geometrías de steps
                steps.flatMap { it.geometry }.distinct()
            }
            OsrmRoutePlan(
                polyline = poly,
                totalDistanceM = if (totalDistanceM > 0) totalDistanceM else along,
                totalDurationS = totalDurationS,
                steps = steps,
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Tests y compat: parse solo steps. */
    fun parseSteps(json: String): List<OsrmStep>? = parseRoutePlan(json)?.steps

    fun actionForStep(step: OsrmStep): BlindNavigationPhraseBuilder.Action {
        val mod = step.modifier?.lowercase().orEmpty()
        val type = step.maneuverType.lowercase()
        val nameBlob = buildString {
            append(step.name.orEmpty().lowercase())
            append(' ')
            append(mod)
            append(' ')
            append(type)
        }
        val looksCross = nameBlob.contains("cruz") ||
            nameBlob.contains("crosswalk") ||
            nameBlob.contains("crossing") ||
            nameBlob.contains("paso de cebra") ||
            type == "notification" && nameBlob.contains("cross")
        return when {
            type == "arrive" -> BlindNavigationPhraseBuilder.Action.ARRIVE
            looksCross -> BlindNavigationPhraseBuilder.Action.CROSS
            type == "depart" || type == "continue" || type == "new name" ->
                BlindNavigationPhraseBuilder.Action.FORWARD
            type.contains("roundabout") -> when {
                mod.contains("left") -> BlindNavigationPhraseBuilder.Action.TURN_LEFT
                mod.contains("right") -> BlindNavigationPhraseBuilder.Action.TURN_RIGHT
                else -> BlindNavigationPhraseBuilder.Action.FORWARD
            }
            mod.contains("uturn") || mod.contains("u-turn") ->
                BlindNavigationPhraseBuilder.Action.U_TURN
            mod.contains("left") -> BlindNavigationPhraseBuilder.Action.TURN_LEFT
            mod.contains("right") -> BlindNavigationPhraseBuilder.Action.TURN_RIGHT
            type == "turn" || type == "end of road" || type == "fork" || type == "off ramp" ->
                when {
                    mod.contains("left") -> BlindNavigationPhraseBuilder.Action.TURN_LEFT
                    mod.contains("right") -> BlindNavigationPhraseBuilder.Action.TURN_RIGHT
                    else -> BlindNavigationPhraseBuilder.Action.OTHER
                }
            else -> BlindNavigationPhraseBuilder.Action.FORWARD
        }
    }

    fun turnSideForStep(step: OsrmStep): TurnSide? {
        return when (actionForStep(step)) {
            BlindNavigationPhraseBuilder.Action.TURN_LEFT -> TurnSide.LEFT
            BlindNavigationPhraseBuilder.Action.TURN_RIGHT -> TurnSide.RIGHT
            BlindNavigationPhraseBuilder.Action.U_TURN -> TurnSide.U_TURN
            else -> null
        }
    }

    fun mapsTypeForStep(step: OsrmStep): io.lazaro.pathguide.MapsInstructionType {
        val type = step.maneuverType.lowercase()
        val action = actionForStep(step)
        return when {
            action == BlindNavigationPhraseBuilder.Action.ARRIVE ->
                io.lazaro.pathguide.MapsInstructionType.ARRIVE
            type.contains("roundabout") ->
                io.lazaro.pathguide.MapsInstructionType.ROUNDABOUT
            action == BlindNavigationPhraseBuilder.Action.TURN_LEFT ||
                action == BlindNavigationPhraseBuilder.Action.TURN_RIGHT ||
                action == BlindNavigationPhraseBuilder.Action.U_TURN ->
                io.lazaro.pathguide.MapsInstructionType.TURN
            action == BlindNavigationPhraseBuilder.Action.CROSS ->
                io.lazaro.pathguide.MapsInstructionType.CROSS_STREET
            action == BlindNavigationPhraseBuilder.Action.FORWARD ->
                io.lazaro.pathguide.MapsInstructionType.STRAIGHT
            else -> io.lazaro.pathguide.MapsInstructionType.OTHER
        }
    }

    private fun parseGeoJsonLine(geometry: JSONObject?): List<OsrmLatLng> {
        if (geometry == null) return emptyList()
        val coords = geometry.optJSONArray("coordinates") ?: return emptyList()
        return buildList {
            for (i in 0 until coords.length()) {
                val pair = coords.optJSONArray(i) ?: continue
                if (pair.length() < 2) continue
                // GeoJSON: [lng, lat]
                add(OsrmLatLng(lat = pair.getDouble(1), lng = pair.getDouble(0)))
            }
        }
    }
}
