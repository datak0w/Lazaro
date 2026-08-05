package io.lazaro.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationBearingTest {

    @Test
    fun relativeBearingLeftAndRight() {
        // Heading north (0), target east (90) → turn right
        assertEquals(90f, NavigationBearing.relativeBearingDeg(0f, 90f), 0.1f)
        assertEquals(
            BlindNavigationPhraseBuilder.Action.TURN_RIGHT,
            NavigationBearing.actionFromRelativeBearing(90f),
        )
        // Heading north, target west (270) → relative -90 → left
        assertEquals(-90f, NavigationBearing.relativeBearingDeg(0f, 270f), 0.1f)
        assertEquals(
            BlindNavigationPhraseBuilder.Action.TURN_LEFT,
            NavigationBearing.actionFromRelativeBearing(-90f),
        )
    }

    @Test
    fun forwardWithinTolerance() {
        assertEquals(
            BlindNavigationPhraseBuilder.Action.FORWARD,
            NavigationBearing.actionFromRelativeBearing(20f),
        )
        assertEquals(
            BlindNavigationPhraseBuilder.Action.FORWARD,
            NavigationBearing.actionFromRelativeBearing(-15f),
        )
    }

    @Test
    fun distanceNearby() {
        val d = NavigationBearing.distanceMeters(36.718, -4.42, 36.719, -4.42)
        assertTrue(d in 90.0..130.0)
    }
}
