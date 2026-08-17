package io.lazaro.navigation

import android.util.Log
import io.lazaro.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rutas a pie vía Google Directions API.
 * Devuelve el mismo [OsrmRoutePlan] que OSRM para reutilizar la guía.
 */
@Singleton
class GoogleDirectionsRouter @Inject constructor() {

    fun isConfigured(): Boolean = BuildConfig.MAPS_API_KEY.isNotBlank()

    fun fetchRoutePlan(
        originLat: Double,
        originLng: Double,
        destLat: Double,
        destLng: Double,
    ): OsrmRoutePlan? {
        val key = BuildConfig.MAPS_API_KEY
        if (key.isBlank()) return null
        val origin = URLEncoder.encode("$originLat,$originLng", Charsets.UTF_8.name())
        val dest = URLEncoder.encode("$destLat,$destLng", Charsets.UTF_8.name())
        val url =
            "https://maps.googleapis.com/maps/api/directions/json" +
                "?origin=$origin&destination=$dest&mode=walking&language=es&key=$key"
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 7_000
                readTimeout = 12_000
                requestMethod = "GET"
            }
            try {
                if (conn.responseCode !in 200..299) {
                    Log.w(TAG, "Directions HTTP ${conn.responseCode}")
                    return null
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                parseDirections(body)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Directions failed: ${e.message}")
            null
        }
    }

    fun parseDirections(json: String): OsrmRoutePlan? {
        return try {
            val root = JSONObject(json)
            val status = root.optString("status")
            if (status != "OK") {
                Log.w(TAG, "Directions status=$status ${root.optString("error_message")}")
                return null
            }
            val routes = root.optJSONArray("routes") ?: return null
            if (routes.length() == 0) return null
            val route = routes.getJSONObject(0)
            val overview = route.optJSONObject("overview_polyline")?.optString("points").orEmpty()
            val polyline = decodePolyline(overview)
            val legs = route.optJSONArray("legs") ?: return null
            if (legs.length() == 0) return null
            val leg = legs.getJSONObject(0)
            val totalDistanceM = leg.optJSONObject("distance")?.optDouble("value", 0.0) ?: 0.0
            val totalDurationS = leg.optJSONObject("duration")?.optDouble("value", 0.0) ?: 0.0
            val stepsArr = leg.optJSONArray("steps") ?: return null
            var along = 0.0
            val steps = buildList {
                for (i in 0 until stepsArr.length()) {
                    val step = stepsArr.getJSONObject(i)
                    val dist = step.optJSONObject("distance")?.optDouble("value", 0.0) ?: 0.0
                    val dur = step.optJSONObject("duration")?.optDouble("value", 0.0) ?: 0.0
                    val start = step.optJSONObject("start_location")
                    val man = step.optString("maneuver").ifBlank { "straight" }
                    val (type, modifier) = mapGoogleManeuver(man)
                    val name = stripHtml(step.optString("html_instructions"))
                        .substringBefore(".")
                        .take(80)
                        .ifBlank { null }
                    val geom = decodePolyline(step.optJSONObject("polyline")?.optString("points").orEmpty())
                    add(
                        OsrmStep(
                            maneuverType = type,
                            modifier = modifier,
                            bearingAfter = bearingFromGeometry(geom),
                            distanceM = dist,
                            durationS = dur,
                            name = name,
                            locationLat = start?.optDouble("lat"),
                            locationLng = start?.optDouble("lng"),
                            startAlongM = along,
                            geometry = geom,
                        ),
                    )
                    along += dist
                }
            }
            if (steps.isEmpty()) return null
            val poly = polyline.ifEmpty { steps.flatMap { it.geometry } }
            OsrmRoutePlan(
                polyline = poly,
                totalDistanceM = if (totalDistanceM > 0) totalDistanceM else along,
                totalDurationS = totalDurationS,
                steps = steps,
            )
        } catch (e: Exception) {
            Log.w(TAG, "parseDirections: ${e.message}")
            null
        }
    }

    private fun mapGoogleManeuver(maneuver: String): Pair<String, String?> {
        val m = maneuver.lowercase()
        return when {
            m.contains("uturn") || m.contains("u-turn") -> "turn" to "uturn"
            m.contains("roundabout") -> "roundabout" to null
            m.contains("turn-left") || m == "turn-slight-left" || m == "turn-sharp-left" ||
                m == "ramp-left" || m == "fork-left" || m == "keep-left" ->
                "turn" to "left"
            m.contains("turn-right") || m == "turn-slight-right" || m == "turn-sharp-right" ||
                m == "ramp-right" || m == "fork-right" || m == "keep-right" ->
                "turn" to "right"
            m.contains("merge") || m.contains("straight") || m == "depart" ->
                "continue" to null
            else -> "continue" to null
        }
    }

    private fun stripHtml(html: String): String {
        return html
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun bearingFromGeometry(geom: List<OsrmLatLng>): Float? {
        if (geom.size < 2) return null
        val a = geom.first()
        val b = geom.last()
        val dLon = Math.toRadians(b.lng - a.lng)
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val y = kotlin.math.sin(dLon) * kotlin.math.cos(lat2)
        val x = kotlin.math.cos(lat1) * kotlin.math.sin(lat2) -
            kotlin.math.sin(lat1) * kotlin.math.cos(lat2) * kotlin.math.cos(dLon)
        val brng = Math.toDegrees(kotlin.math.atan2(y, x))
        return ((brng + 360.0) % 360.0).toFloat()
    }

    /** Decodifica polyline encoded de Google. */
    fun decodePolyline(encoded: String): List<OsrmLatLng> {
        if (encoded.isBlank()) return emptyList()
        val poly = ArrayList<OsrmLatLng>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0
        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20 && index < len)
            val dlat = if ((result and 1) != 0) (result shr 1).inv() else result shr 1
            lat += dlat
            shift = 0
            result = 0
            if (index >= len) break
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20 && index < len)
            val dlng = if ((result and 1) != 0) (result shr 1).inv() else result shr 1
            lng += dlng
            poly.add(OsrmLatLng(lat = lat / 1e5, lng = lng / 1e5))
        }
        return poly
    }

    companion object {
        private const val TAG = "GoogleDirections"
    }
}
