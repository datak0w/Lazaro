package io.lazaro.routes.map

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Proyecta coordenadas GPS sobre el eje del corredor ODM.
 */
object CorridorSnapEngine {

    fun snap(
        lat: Double,
        lng: Double,
        bundle: CorridorBundle,
        accuracyM: Float = 10f,
    ): CorridorSnapResult? {
        if (bundle.isEmpty) return null
        val path = bundle.path
        var bestLateral = Double.MAX_VALUE
        var bestAlong = 0f
        var bestLat = lat
        var bestLng = lng
        var bestBearing = 0f
        var accumulated = 0.0

        for (i in 0 until path.size - 1) {
            val a = path[i]
            val b = path[i + 1]
            val segLen = haversineM(a.lat, a.lng, b.lat, b.lng)
            if (segLen < 0.01) continue

            val projection = projectOnSegment(lat, lng, a.lat, a.lng, b.lat, b.lng)
            if (projection.lateralM < bestLateral) {
                bestLateral = projection.lateralM
                bestAlong = (accumulated + projection.alongSegM).toFloat()
                bestLat = projection.lat
                bestLng = projection.lng
                bestBearing = bearingDeg(a.lat, a.lng, b.lat, b.lng)
            }
            accumulated += segLen
        }

        val profilePoint = profileAt(bundle, bestAlong)
        val widthM = profilePoint?.widthM ?: 2.5f
        val onCorridor = bestLateral <= (widthM + OFF_CORridor_TOLERANCE_M)
            .coerceAtLeast(accuracyM * 0.6f)
            .toDouble()

        val lateralScore = (1f - (bestLateral / (widthM + 1.5).toDouble()).toFloat()).coerceIn(0f, 1f)
        val accuracyBoost = if (accuracyM > 12f) 0.15f else 0f
        val odmScore = if (onCorridor) {
            (lateralScore * 0.85f + 0.15f + accuracyBoost).coerceIn(0f, 1f)
        } else {
            (lateralScore * 0.5f).coerceIn(0f, 0.45f)
        }

        return CorridorSnapResult(
            lat = bestLat,
            lng = bestLng,
            distanceAlongM = bestAlong,
            lateralOffsetM = bestLateral.toFloat(),
            bearingDeg = profilePoint?.bearingDeg ?: bestBearing,
            widthM = widthM,
            gradePct = profilePoint?.gradePct ?: 0f,
            segmentTag = profilePoint?.segmentTag ?: "unknown",
            odmScore = odmScore,
            onCorridor = onCorridor,
        )
    }

    fun profileAt(bundle: CorridorBundle, alongM: Float): CorridorProfilePoint? {
        if (bundle.profile.isEmpty()) return null
        return bundle.profile.minByOrNull { kotlin.math.abs(it.distanceAlongM - alongM) }
    }

    fun nodeAhead(bundle: CorridorBundle, alongM: Float, lookaheadM: Float = 25f): CorridorNode? {
        return bundle.nodes
            .filter { it.distanceAlongM in alongM..(alongM + lookaheadM) }
            .minByOrNull { it.distanceAlongM }
    }

    private data class SegmentProjection(
        val lat: Double,
        val lng: Double,
        val alongSegM: Double,
        val lateralM: Double,
    )

    private fun projectOnSegment(
        pLat: Double,
        pLng: Double,
        aLat: Double,
        aLng: Double,
        bLat: Double,
        bLng: Double,
    ): SegmentProjection {
        val ax = aLng
        val ay = aLat
        val bx = bLng
        val by = bLat
        val px = pLng
        val py = pLat

        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        if (lenSq < 1e-12) {
            val d = haversineM(pLat, pLng, aLat, aLng)
            return SegmentProjection(aLat, aLng, 0.0, d)
        }

        var t = ((px - ax) * dx + (py - ay) * dy) / lenSq
        t = t.coerceIn(0.0, 1.0)

        val projLng = ax + t * dx
        val projLat = ay + t * dy
        val alongM = haversineM(aLat, aLng, projLat, projLng)
        val lateralM = haversineM(pLat, pLng, projLat, projLng)
        return SegmentProjection(projLat, projLng, alongM, lateralM)
    }

    private fun bearingDeg(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val dLng = Math.toRadians(lng2 - lng1)
        val y = sin(dLng) * cos(Math.toRadians(lat2))
        val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
            sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(dLng)
        return ((Math.toDegrees(atan2(y, x)) + 360) % 360).toFloat()
    }

    private fun haversineM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private const val OFF_CORridor_TOLERANCE_M = 1.8f
}
