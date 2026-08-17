package io.lazaro.routes.editor

import io.lazaro.routes.entity.SavedRoute
import io.lazaro.routes.model.CanonicalProfilePoint
import io.lazaro.routes.model.RouteCodec
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Documento intercambiable editor web ↔ app (`.lazaro-route.json`).
 *
 * Incluye traza, acera por tramo, cruces y anuncios de localizaciones
 * («estás frente al cementerio…»).
 */
data class LazaroRouteDocument(
    val version: Int = VERSION,
    val name: String,
    val destinationLabel: String? = null,
    val waypoints: List<LatLngPoint>,
    val sidewalkSides: List<SidewalkSpan> = emptyList(),
    val crossings: List<RouteCrossing> = emptyList(),
    val announcements: List<RouteAnnouncement> = emptyList(),
) {
    data class LatLngPoint(val lat: Double, val lng: Double)
    data class SidewalkSpan(
        val fromIndex: Int,
        val toIndex: Int,
        val side: String, // LEFT | RIGHT | EITHER
    )
    data class RouteCrossing(
        val lat: Double,
        val lng: Double,
        val label: String = "Paso de cebra",
        val radiusM: Float = 18f,
    )
    data class RouteAnnouncement(
        val lat: Double,
        val lng: Double,
        val text: String,
        val radiusM: Float = 18f,
        val id: String = "",
    )

    companion object {
        const val FORMAT = "lazaro-route"
        const val VERSION = 1
        const val FILE_EXTENSION = "lazaro-route.json"
    }
}

object LazaroRouteDocumentCodec {

    fun toJson(doc: LazaroRouteDocument): String {
        val root = JSONObject()
            .put("format", LazaroRouteDocument.FORMAT)
            .put("version", doc.version)
            .put("name", doc.name)
            .put("destinationLabel", doc.destinationLabel)
        val wps = JSONArray()
        for (p in doc.waypoints) {
            wps.put(JSONObject().put("lat", p.lat).put("lng", p.lng))
        }
        root.put("waypoints", wps)
        val sides = JSONArray()
        for (s in doc.sidewalkSides) {
            sides.put(
                JSONObject()
                    .put("fromIndex", s.fromIndex)
                    .put("toIndex", s.toIndex)
                    .put("side", s.side.uppercase()),
            )
        }
        root.put("sidewalkSides", sides)
        val crossings = JSONArray()
        for (c in doc.crossings) {
            crossings.put(
                JSONObject()
                    .put("lat", c.lat)
                    .put("lng", c.lng)
                    .put("label", c.label)
                    .put("radiusM", c.radiusM.toDouble()),
            )
        }
        root.put("crossings", crossings)
        val anns = JSONArray()
        for (a in doc.announcements) {
            anns.put(
                JSONObject()
                    .put("lat", a.lat)
                    .put("lng", a.lng)
                    .put("text", a.text)
                    .put("radiusM", a.radiusM.toDouble())
                    .put("id", a.id.ifBlank { "ann-${a.lat}-${a.lng}" }),
            )
        }
        root.put("announcements", anns)
        return root.toString(2)
    }

    fun fromJson(raw: String): LazaroRouteDocument? {
        return try {
            val root = JSONObject(raw)
            val format = root.optString("format")
            if (format.isNotBlank() && format != LazaroRouteDocument.FORMAT) return null
            val name = root.optString("name").trim()
            if (name.isBlank()) return null
            val wpsArr = root.optJSONArray("waypoints") ?: return null
            if (wpsArr.length() < 2) return null
            val waypoints = buildList {
                for (i in 0 until wpsArr.length()) {
                    val o = wpsArr.getJSONObject(i)
                    add(
                        LazaroRouteDocument.LatLngPoint(
                            lat = o.getDouble("lat"),
                            lng = o.getDouble("lng"),
                        ),
                    )
                }
            }
            val sides = buildList {
                val arr = root.optJSONArray("sidewalkSides") ?: return@buildList
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        LazaroRouteDocument.SidewalkSpan(
                            fromIndex = o.getInt("fromIndex"),
                            toIndex = o.getInt("toIndex"),
                            side = o.optString("side", "UNKNOWN").uppercase(),
                        ),
                    )
                }
            }
            val crossings = buildList {
                val arr = root.optJSONArray("crossings") ?: return@buildList
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        LazaroRouteDocument.RouteCrossing(
                            lat = o.getDouble("lat"),
                            lng = o.getDouble("lng"),
                            label = o.optString("label", "Paso de cebra"),
                            radiusM = o.optDouble("radiusM", 18.0).toFloat(),
                        ),
                    )
                }
            }
            val announcements = buildList {
                val arr = root.optJSONArray("announcements") ?: return@buildList
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val text = o.optString("text").trim()
                    if (text.isBlank()) continue
                    add(
                        LazaroRouteDocument.RouteAnnouncement(
                            lat = o.getDouble("lat"),
                            lng = o.getDouble("lng"),
                            text = text,
                            radiusM = o.optDouble("radiusM", 18.0).toFloat(),
                            id = o.optString("id"),
                        ),
                    )
                }
            }
            LazaroRouteDocument(
                version = root.optInt("version", LazaroRouteDocument.VERSION),
                name = name,
                destinationLabel = root.optString("destinationLabel").takeIf { it.isNotBlank() },
                waypoints = waypoints,
                sidewalkSides = sides,
                crossings = crossings,
                announcements = announcements,
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Convierte el documento en [SavedRoute] listo para Room. */
    fun toSavedRoute(doc: LazaroRouteDocument, existingId: Long = 0): SavedRoute {
        val polyPairs = doc.waypoints.map { it.lat to it.lng }
        val profile = buildProfile(doc)
        val length = totalLengthM(polyPairs)
        val start = doc.waypoints.first()
        val end = doc.waypoints.last()
        return SavedRoute(
            id = existingId,
            name = doc.name.trim(),
            destinationKey = doc.name.trim().lowercase().replace(Regex("\\s+"), "_"),
            destinationLabel = doc.destinationLabel?.trim() ?: doc.name.trim(),
            startLat = start.lat,
            startLng = start.lng,
            endLat = end.lat,
            endLng = end.lng,
            canonicalPolyline = RouteCodec.encodePolyline(polyPairs),
            canonicalProfileJson = RouteCodec.encodeProfile(profile),
            runCount = 0,
            qualityScore = 0.95f,
            totalLengthM = length,
            editorDocumentJson = toJson(doc),
            updatedAt = System.currentTimeMillis(),
        )
    }

    fun parseStoredDocument(route: SavedRoute): LazaroRouteDocument? {
        if (route.editorDocumentJson.isNotBlank()) {
            fromJson(route.editorDocumentJson)?.let { return it }
        }
        // Fallback: solo polyline grabada
        val poly = RouteCodec.decodePolyline(route.canonicalPolyline)
        if (poly.size < 2) return null
        return LazaroRouteDocument(
            name = route.name,
            destinationLabel = route.destinationLabel,
            waypoints = poly.map { LazaroRouteDocument.LatLngPoint(it.first, it.second) },
        )
    }

    private fun buildProfile(doc: LazaroRouteDocument): List<CanonicalProfilePoint> {
        var along = 0f
        return buildList {
            for (i in doc.waypoints.indices) {
                val p = doc.waypoints[i]
                if (i > 0) {
                    val prev = doc.waypoints[i - 1]
                    along += haversineM(prev.lat, prev.lng, p.lat, p.lng)
                }
                val side = sideForIndex(doc.sidewalkSides, i)
                val yaw = if (i < doc.waypoints.lastIndex) {
                    bearingDeg(p.lat, p.lng, doc.waypoints[i + 1].lat, doc.waypoints[i + 1].lng)
                } else if (i > 0) {
                    bearingDeg(doc.waypoints[i - 1].lat, doc.waypoints[i - 1].lng, p.lat, p.lng)
                } else {
                    0f
                }
                val nearCross = doc.crossings.any { c ->
                    haversineM(p.lat, p.lng, c.lat, c.lng) <= c.radiusM
                }
                add(
                    CanonicalProfilePoint(
                        distanceAlongM = along,
                        leftP = if (side == "LEFT") 0.55f else 0.25f,
                        centerP = 0.2f,
                        rightP = if (side == "RIGHT") 0.55f else 0.25f,
                        safeSide = side,
                        roadSide = when (side) {
                            "LEFT" -> "RIGHT"
                            "RIGHT" -> "LEFT"
                            else -> "UNKNOWN"
                        },
                        yawDeg = yaw,
                        segmentType = if (nearCross) "crossing" else "urban_sidewalk",
                        lat = p.lat,
                        lng = p.lng,
                    ),
                )
            }
        }
    }

    private fun sideForIndex(spans: List<LazaroRouteDocument.SidewalkSpan>, index: Int): String {
        val hit = spans.lastOrNull { index in it.fromIndex..it.toIndex } ?: return "UNKNOWN"
        return when (hit.side.uppercase()) {
            "LEFT", "RIGHT", "EITHER" -> hit.side.uppercase()
            else -> "UNKNOWN"
        }
    }

    private fun totalLengthM(points: List<Pair<Double, Double>>): Float {
        var sum = 0f
        for (i in 1 until points.size) {
            sum += haversineM(points[i - 1].first, points[i - 1].second, points[i].first, points[i].second)
        }
        return sum
    }

    fun haversineM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return (2 * r * atan2(sqrt(a), sqrt(1 - a))).toFloat()
    }

    private fun bearingDeg(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val dLng = Math.toRadians(lng2 - lng1)
        val lat1r = Math.toRadians(lat1)
        val lat2r = Math.toRadians(lat2)
        val y = sin(dLng) * cos(lat2r)
        val x = cos(lat1r) * sin(lat2r) - sin(lat1r) * cos(lat2r) * cos(dLng)
        val brng = Math.toDegrees(atan2(y, x))
        return ((brng + 360.0) % 360.0).toFloat()
    }
}
