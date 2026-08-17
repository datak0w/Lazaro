package io.lazaro.navigation

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class PolylineProgress(
    val alongM: Double,
    val remainingM: Double,
    val lateralOffsetM: Double,
    val nearestIndex: Int,
    val offRoute: Boolean,
)

/**
 * Snap GPS a polilínea de ruta: distancia-along, offset lateral y off-route.
 */
class PolylineProgressTracker {

    private var points: List<OsrmLatLng> = emptyList()
    private var cumulativeM: DoubleArray = DoubleArray(0)
    private var totalM: Double = 0.0
    private var lastIndex: Int = 0

    fun clear() {
        points = emptyList()
        cumulativeM = DoubleArray(0)
        totalM = 0.0
        lastIndex = 0
    }

    fun load(polyline: List<OsrmLatLng>, totalDistanceM: Double = 0.0) {
        points = polyline
        if (polyline.size < 2) {
            cumulativeM = DoubleArray(polyline.size)
            totalM = totalDistanceM.coerceAtLeast(0.0)
            lastIndex = 0
            return
        }
        cumulativeM = DoubleArray(polyline.size)
        var sum = 0.0
        for (i in 1 until polyline.size) {
            sum += haversineM(
                polyline[i - 1].lat, polyline[i - 1].lng,
                polyline[i].lat, polyline[i].lng,
            )
            cumulativeM[i] = sum
        }
        totalM = if (totalDistanceM > 0) totalDistanceM else sum
        lastIndex = 0
    }

    fun isLoaded(): Boolean = points.size >= 2

    fun update(lat: Double, lng: Double, offRouteThresholdM: Double = 35.0): PolylineProgress {
        if (!isLoaded()) {
            return PolylineProgress(
                alongM = 0.0,
                remainingM = totalM,
                lateralOffsetM = Double.MAX_VALUE,
                nearestIndex = 0,
                offRoute = false,
            )
        }
        val searchFrom = (lastIndex - 8).coerceAtLeast(0)
        val searchTo = (lastIndex + 40).coerceAtMost(points.size - 2)
        var bestDist = Double.MAX_VALUE
        var bestAlong = 0.0
        var bestIndex = lastIndex
        for (i in searchFrom..searchTo) {
            val a = points[i]
            val b = points[i + 1]
            val proj = projectOnSegment(lat, lng, a.lat, a.lng, b.lat, b.lng)
            if (proj.distanceM < bestDist) {
                bestDist = proj.distanceM
                bestAlong = cumulativeM[i] + proj.alongSegM
                bestIndex = i
            }
        }
        // Si el window local falla (muy lejos), búsqueda global
        if (bestDist > offRouteThresholdM * 1.5) {
            for (i in 0 until points.size - 1) {
                val a = points[i]
                val b = points[i + 1]
                val proj = projectOnSegment(lat, lng, a.lat, a.lng, b.lat, b.lng)
                if (proj.distanceM < bestDist) {
                    bestDist = proj.distanceM
                    bestAlong = cumulativeM[i] + proj.alongSegM
                    bestIndex = i
                }
            }
        }
        lastIndex = bestIndex
        val along = bestAlong.coerceIn(0.0, totalM)
        return PolylineProgress(
            alongM = along,
            remainingM = (totalM - along).coerceAtLeast(0.0),
            lateralOffsetM = bestDist,
            nearestIndex = bestIndex,
            offRoute = bestDist > offRouteThresholdM,
        )
    }

    private data class SegProj(val distanceM: Double, val alongSegM: Double)

    private fun projectOnSegment(
        lat: Double,
        lng: Double,
        aLat: Double,
        aLng: Double,
        bLat: Double,
        bLng: Double,
    ): SegProj {
        val ax = aLng
        val ay = aLat
        val bx = bLng
        val by = bLat
        val px = lng
        val py = lat
        val abx = bx - ax
        val aby = by - ay
        val apx = px - ax
        val apy = py - ay
        val ab2 = abx * abx + aby * aby
        val t = if (ab2 < 1e-14) 0.0 else ((apx * abx + apy * aby) / ab2).coerceIn(0.0, 1.0)
        val qx = ax + t * abx
        val qy = ay + t * aby
        val dist = haversineM(lat, lng, qy, qx)
        val segLen = haversineM(aLat, aLng, bLat, bLng)
        return SegProj(distanceM = dist, alongSegM = t * segLen)
    }

    companion object {
        fun haversineM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLng = Math.toRadians(lng2 - lng1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLng / 2) * sin(dLng / 2)
            return r * 2 * atan2(sqrt(a), sqrt(1 - a))
        }
    }
}
