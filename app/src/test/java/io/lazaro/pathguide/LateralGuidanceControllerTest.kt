package io.lazaro.pathguide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LateralGuidanceControllerTest {

    private val controller = LateralGuidanceController()

    @Test
    fun `offset dentro de deadband es zona segura`() {
        val walkable = WalkableCorridor(
            lateralOffsetNorm = 0.05f,
            confidence = 0.8f,
        )
        val layout = StreetLayoutState(alignment = SidewalkAlignment.ON_SIDEWALK)
        val corridor = CorridorState()

        val signal = controller.compute(
            walkable = walkable,
            layout = layout,
            corridor = corridor,
            dangerLevel = SidewalkNotificationSystem.Level.OK,
        )

        assertTrue(signal.inSafeZone)
        assertEquals(0f, signal.leftBeep, 0.001f)
        assertEquals(0f, signal.rightBeep, 0.001f)
    }

    @Test
    fun `offset positivo genera pitido derecho`() {
        val walkable = WalkableCorridor(
            lateralOffsetNorm = 0.45f,
            confidence = 0.8f,
        )
        val layout = StreetLayoutState(alignment = SidewalkAlignment.ON_SIDEWALK)
        val corridor = CorridorState()

        val signal = controller.compute(
            walkable = walkable,
            layout = layout,
            corridor = corridor,
            dangerLevel = SidewalkNotificationSystem.Level.OK,
        )

        assertTrue(signal.rightBeep > 0.2f)
        assertEquals(0f, signal.leftBeep, 0.001f)
        assertTrue(signal.continuous)
    }

    @Test
    fun `peligro en calzada activa warning`() {
        val walkable = WalkableCorridor(confidence = 0.8f)
        val layout = StreetLayoutState(
            alignment = SidewalkAlignment.ON_ROAD,
            safeSide = RoadSide.LEFT,
        )
        val corridor = CorridorState()

        val signal = controller.compute(
            walkable = walkable,
            layout = layout,
            corridor = corridor,
            dangerLevel = SidewalkNotificationSystem.Level.ROAD,
        )

        assertTrue(signal.warning)
        assertTrue(signal.leftBeep >= 0.75f)
    }
}
