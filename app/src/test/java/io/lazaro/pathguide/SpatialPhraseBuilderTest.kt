package io.lazaro.pathguide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialPhraseBuilderTest {

    @Test
    fun closeRangeReportsSubMeter() {
        val meters = SpatialPhraseBuilder.estimateMeters(closeRange = true)
        assertTrue(meters < 1f)
        assertEquals("menos de 1 metro", SpatialPhraseBuilder.formatDistance(meters))
    }

    @Test
    fun frontalBlockedFurnitureIsClose() {
        val corridor = CorridorState(
            centerProximity = 0.28f,
            leftProximity = 0.15f,
            rightProximity = 0.12f,
            isFrontallyBlocked = true,
            frontalSeverity = 0.42f,
            frontalCloseRange = true,
        )
        val meters = SpatialPhraseBuilder.objectDistanceForCorridor(corridor)
        assertTrue("Obstáculo cercano no debe sonar a 5m: $meters", meters <= 1.8f)
    }

    @Test
    fun lowProximityWithoutFrontalIsFarther() {
        val corridor = CorridorState(
            centerProximity = 0.10f,
            leftProximity = 0.08f,
            rightProximity = 0.07f,
            isFrontallyBlocked = false,
            frontalSeverity = 0.05f,
        )
        val meters = SpatialPhraseBuilder.objectDistanceForCorridor(corridor)
        assertTrue(meters >= 4f)
    }

    @Test
    fun seeObjectPhraseUsesCloseDistanceWhenBlocked() {
        val corridor = CorridorState(
            centerProximity = 0.31f,
            isFrontallyBlocked = true,
            frontalSeverity = 0.48f,
            frontalCloseRange = true,
        )
        val phrase = SpatialPhraseBuilder.seeObjectPhrase("mueble", corridor)
        assertTrue(phrase.contains("menos de 1 metro") || phrase.contains("1 metro"))
    }
}
