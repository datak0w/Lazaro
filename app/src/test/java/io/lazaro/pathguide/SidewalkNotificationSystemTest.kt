package io.lazaro.pathguide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SidewalkNotificationSystemTest {

    @Test
    fun roadDangerBeepsTowardSafeSideWithoutVoice() {
        val layout = StreetLayoutState(
            roadSide = RoadSide.RIGHT,
            safeSide = RoadSide.LEFT,
            alignment = SidewalkAlignment.ON_ROAD,
            driftScore = 1f,
        )
        val result = SidewalkNotificationSystem.evaluate(
            layout = layout,
            corridor = CorridorState(),
            now = 10_000L,
            lastVoiceMs = 0L,
            lastRecoveredMs = 0L,
            wasOffSidewalk = true,
            hugAnnounced = true,
        )
        val signal = result.signal
        assertEquals(SidewalkNotificationSystem.Level.ROAD, signal.level)
        assertTrue(signal.continuous)
        assertTrue(signal.leftBeep > signal.rightBeep)
        assertNull(signal.voiceCue)
        assertFalse(signal.inSafeZone)
    }

    @Test
    fun driftBeepsTowardSafeSideWithoutVoice() {
        val layout = StreetLayoutState(
            roadSide = RoadSide.RIGHT,
            safeSide = RoadSide.LEFT,
            alignment = SidewalkAlignment.DRIFTING_TO_ROAD,
            driftScore = 0.5f,
        )
        val result = SidewalkNotificationSystem.evaluate(
            layout = layout,
            corridor = CorridorState(leftProximity = 0.2f),
            now = 10_000L,
            lastVoiceMs = 0L,
            lastRecoveredMs = 0L,
            wasOffSidewalk = true,
            hugAnnounced = true,
        )
        val signal = result.signal
        assertEquals(SidewalkNotificationSystem.Level.DRIFT, signal.level)
        assertTrue(signal.leftBeep > signal.rightBeep)
        assertNull(signal.voiceCue)
        assertFalse(signal.inSafeZone)
    }

    @Test
    fun recoveredHasNoVoiceOnlyHaptic() {
        val layout = StreetLayoutState(
            roadSide = RoadSide.RIGHT,
            safeSide = RoadSide.LEFT,
            alignment = SidewalkAlignment.ON_SIDEWALK,
            centeringScore = 0.85f,
            driftScore = 0.05f,
        )
        val result = SidewalkNotificationSystem.evaluate(
            layout = layout,
            corridor = CorridorState(leftProximity = 0.4f),
            now = 20_000L,
            lastVoiceMs = 0L,
            lastRecoveredMs = 0L,
            wasOffSidewalk = true,
            hugAnnounced = true,
        )
        assertNull(result.signal.voiceCue)
        assertEquals(SidewalkNotificationSystem.Signal.Haptic.RECOVERED, result.signal.haptic)
    }

    @Test
    fun centeredSafeZoneIsSilentNoVoice() {
        val layout = StreetLayoutState(
            roadSide = RoadSide.RIGHT,
            safeSide = RoadSide.LEFT,
            alignment = SidewalkAlignment.ON_SIDEWALK,
            centeringScore = 0.85f,
            driftScore = 0.05f,
        )
        val result = SidewalkNotificationSystem.evaluate(
            layout = layout,
            corridor = CorridorState(),
            now = 1_000L,
            lastVoiceMs = 0L,
            lastRecoveredMs = 0L,
            wasOffSidewalk = false,
            hugAnnounced = false,
        )
        assertTrue(result.signal.inSafeZone)
        assertEquals(0f, result.signal.leftBeep, 0.01f)
        assertNull(result.signal.voiceCue)
    }

    @Test
    fun offCenterBeepsTowardSafeSideContinuous() {
        val layout = StreetLayoutState(
            roadSide = RoadSide.RIGHT,
            safeSide = RoadSide.LEFT,
            alignment = SidewalkAlignment.ON_SIDEWALK,
            centeringScore = 0.25f,
            driftScore = 0.10f,
        )
        val result = SidewalkNotificationSystem.evaluate(
            layout = layout,
            corridor = CorridorState(),
            now = 5_000L,
            lastVoiceMs = 0L,
            lastRecoveredMs = 0L,
            wasOffSidewalk = false,
            hugAnnounced = true,
        )
        assertFalse(result.signal.inSafeZone)
        assertTrue(result.signal.leftBeep > result.signal.rightBeep)
        assertTrue(result.signal.continuous)
        assertNull(result.signal.voiceCue)
    }
}
