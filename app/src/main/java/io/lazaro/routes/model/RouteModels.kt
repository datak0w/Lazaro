package io.lazaro.routes.model

import io.lazaro.pathguide.CorridorState
import io.lazaro.pathguide.JunctionType
import io.lazaro.pathguide.OutdoorNavPhase
import io.lazaro.pathguide.RoadSide
import io.lazaro.routes.entity.RouteObservation
import org.json.JSONArray
import org.json.JSONObject

data class CanonicalProfilePoint(
    val distanceAlongM: Float,
    val leftP: Float,
    val centerP: Float,
    val rightP: Float,
    val safeSide: String,
    val roadSide: String,
    val yawDeg: Float,
    val segmentType: String,
    val lat: Double,
    val lng: Double,
)

data class RouteMatchState(
    val routeId: Long,
    val polylineIndex: Int,
    val profileIndex: Int = 0,
    val distanceAlongM: Float,
    val lateralOffsetM: Float,
    val confidence: Float,
    val inReplaySegment: Boolean,
    val expectedPoint: CanonicalProfilePoint?,
    val heatmapObstacleHits: Int = 0,
    val heatmapVariance: Float = 0f,
    val heatmapSafeSide: String = "UNKNOWN",
    val heatmapMeanLeft: Float = 0f,
    val heatmapMeanRight: Float = 0f,
    val heatmapConfidence: Float = 0f,
    val odmScore: Float = 0f,
    val odmAlongM: Float = 0f,
    val odmGradePct: Float = 0f,
    val onOdmCorridor: Boolean = false,
)

object RouteCodec {
    fun encodePolyline(points: List<Pair<Double, Double>>): String {
        val arr = JSONArray()
        for ((lat, lng) in points) {
            arr.put(JSONArray().put(lat).put(lng))
        }
        return arr.toString()
    }

    fun decodePolyline(raw: String): List<Pair<Double, Double>> {
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val pair = arr.getJSONArray(i)
                    add(pair.getDouble(0) to pair.getDouble(1))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun encodeProfile(points: List<CanonicalProfilePoint>): String {
        val arr = JSONArray()
        for (p in points) {
            arr.put(
                JSONObject()
                    .put("d", p.distanceAlongM.toDouble())
                    .put("l", p.leftP.toDouble())
                    .put("c", p.centerP.toDouble())
                    .put("r", p.rightP.toDouble())
                    .put("safe", p.safeSide)
                    .put("road", p.roadSide)
                    .put("yaw", p.yawDeg.toDouble())
                    .put("seg", p.segmentType)
                    .put("lat", p.lat)
                    .put("lng", p.lng),
            )
        }
        return arr.toString()
    }

    fun decodeProfile(raw: String): List<CanonicalProfilePoint> {
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        CanonicalProfilePoint(
                            distanceAlongM = o.getDouble("d").toFloat(),
                            leftP = o.getDouble("l").toFloat(),
                            centerP = o.getDouble("c").toFloat(),
                            rightP = o.getDouble("r").toFloat(),
                            safeSide = o.optString("safe", "UNKNOWN"),
                            roadSide = o.optString("road", "UNKNOWN"),
                            yawDeg = o.optDouble("yaw", 0.0).toFloat(),
                            segmentType = o.optString("seg", "unknown"),
                            lat = o.optDouble("lat", 0.0),
                            lng = o.optDouble("lng", 0.0),
                        ),
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

fun RouteObservation.toSample(): RouteSample = RouteSample(
    lat = lat,
    lng = lng,
    accuracyM = accuracyM,
    bearingDeg = bearingDeg,
    yawDeg = yawDeg,
    leftP = leftP,
    centerP = centerP,
    rightP = rightP,
    safeSide = safeSide,
    roadSide = roadSide,
    junction = junction,
    phase = phase,
    segmentType = segmentType,
    obstacleLabel = obstacleLabel,
    distanceAlongM = distanceAlongM,
    timestampMs = timestampMs,
)

data class RouteSample(
    val lat: Double,
    val lng: Double,
    val accuracyM: Float,
    val bearingDeg: Float,
    val yawDeg: Float,
    val leftP: Float,
    val centerP: Float,
    val rightP: Float,
    val safeSide: String,
    val roadSide: String,
    val junction: String,
    val phase: String,
    val segmentType: String,
    val obstacleLabel: String?,
    val distanceAlongM: Float,
    val timestampMs: Long,
)

fun CorridorState.toSides(): Triple<Float, Float, Float> = Triple(leftProximity, centerProximity, rightProximity)

fun RoadSide.toStorage(): String = name

fun JunctionType.toStorage(): String = name

fun OutdoorNavPhase.toStorage(): String = name
