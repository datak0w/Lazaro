package io.lazaro.navigation

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Utilidades de rumbo / distancia para guía propia. */
object NavigationBearing {

    private const val FORWARD_TOLERANCE_DEG = 30f

    fun bearingDeg(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val dLng = Math.toRadians(lng2 - lng1)
        val y = sin(dLng) * cos(Math.toRadians(lat2))
        val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
            sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(dLng)
        return ((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0).toFloat()
    }

    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLng / 2) * sin(dLng / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Diferencia heading→target en (−180, 180]: negativo = izquierda. */
    fun relativeBearingDeg(headingDeg: Float, targetBearingDeg: Float): Float {
        var delta = targetBearingDeg - headingDeg
        while (delta > 180f) delta -= 360f
        while (delta <= -180f) delta += 360f
        return delta
    }

    fun actionFromRelativeBearing(relativeDeg: Float): BlindNavigationPhraseBuilder.Action {
        val mag = kotlin.math.abs(relativeDeg)
        return when {
            mag <= FORWARD_TOLERANCE_DEG -> BlindNavigationPhraseBuilder.Action.FORWARD
            relativeDeg < 0f -> BlindNavigationPhraseBuilder.Action.TURN_LEFT
            else -> BlindNavigationPhraseBuilder.Action.TURN_RIGHT
        }
    }

    fun normalizeHeadingDeg(deg: Float): Float {
        var d = deg % 360f
        if (d < 0f) d += 360f
        return d
    }
}
