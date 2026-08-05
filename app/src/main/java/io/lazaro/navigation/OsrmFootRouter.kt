package io.lazaro.navigation

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

data class OsrmStep(
    val maneuverType: String,
    val modifier: String?,
    val bearingAfter: Float?,
    val distanceM: Double,
    val locationLat: Double?,
    val locationLng: Double?,
)

@Singleton
class OsrmFootRouter @Inject constructor() {

    fun fetchSteps(
        originLat: Double,
        originLng: Double,
        destLat: Double,
        destLng: Double,
    ): List<OsrmStep>? {
        val url =
            "https://router.project-osrm.org/route/v1/foot/" +
                "$originLng,$originLat;$destLng,$destLat" +
                "?steps=true&overview=false&geometries=geojson"
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6_000
                readTimeout = 8_000
                requestMethod = "GET"
            }
            try {
                if (conn.responseCode !in 200..299) return null
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                parseSteps(body)
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    fun parseSteps(json: String): List<OsrmStep>? {
        return try {
            val root = JSONObject(json)
            if (root.optString("code") != "Ok") return null
            val routes = root.optJSONArray("routes") ?: return null
            if (routes.length() == 0) return null
            val legs = routes.getJSONObject(0).optJSONArray("legs") ?: return null
            if (legs.length() == 0) return null
            val steps = legs.getJSONObject(0).optJSONArray("steps") ?: return null
            buildList {
                for (i in 0 until steps.length()) {
                    val step = steps.getJSONObject(i)
                    val man = step.optJSONObject("maneuver") ?: continue
                    val loc = man.optJSONArray("location")
                    add(
                        OsrmStep(
                            maneuverType = man.optString("type", "turn"),
                            modifier = man.optString("modifier").ifBlank { null },
                            bearingAfter = if (man.has("bearing_after")) {
                                man.optDouble("bearing_after").toFloat()
                            } else {
                                null
                            },
                            distanceM = step.optDouble("distance", 0.0),
                            locationLat = loc?.optDouble(1),
                            locationLng = loc?.optDouble(0),
                        ),
                    )
                }
            }.ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    fun actionForStep(step: OsrmStep): BlindNavigationPhraseBuilder.Action {
        val mod = step.modifier?.lowercase().orEmpty()
        val type = step.maneuverType.lowercase()
        return when {
            type == "arrive" -> BlindNavigationPhraseBuilder.Action.ARRIVE
            type == "depart" || type == "continue" || type == "new name" ->
                BlindNavigationPhraseBuilder.Action.FORWARD
            mod.contains("uturn") || mod.contains("u-turn") ->
                BlindNavigationPhraseBuilder.Action.U_TURN
            mod.contains("left") -> BlindNavigationPhraseBuilder.Action.TURN_LEFT
            mod.contains("right") -> BlindNavigationPhraseBuilder.Action.TURN_RIGHT
            type.contains("roundabout") -> when {
                mod.contains("left") -> BlindNavigationPhraseBuilder.Action.TURN_LEFT
                mod.contains("right") -> BlindNavigationPhraseBuilder.Action.TURN_RIGHT
                else -> BlindNavigationPhraseBuilder.Action.FORWARD
            }
            else -> BlindNavigationPhraseBuilder.Action.FORWARD
        }
    }
}
