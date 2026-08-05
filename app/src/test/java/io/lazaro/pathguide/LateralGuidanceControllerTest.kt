package io.lazaro.pathguide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertFalse(signal.continuous)
    }

    @Test
    fun `pared derecha pita aunque offset centrado`() {
        controller.reset()
        val walkable = WalkableCorridor(
            lateralOffsetNorm = 0.02f,
            confidence = 0.9f,
        )
        val layout = StreetLayoutState(
            alignment = SidewalkAlignment.ON_SIDEWALK,
            safeSide = RoadSide.RIGHT,
            centeringScore = 0.66f,
        )
        val corridor = CorridorState(
            leftProximity = 0.35f,
            centerProximity = 0.30f,
            rightProximity = 0.48f,
        )

        val signal = controller.compute(
            walkable = walkable,
            layout = layout,
            corridor = corridor,
            dangerLevel = SidewalkNotificationSystem.Level.OK,
        )

        assertFalse(signal.inSafeZone)
        assertTrue(signal.rightBeep > 0.25f)
        assertEquals(0f, signal.leftBeep, 0.001f)
    }

    @Test
    fun `giro IMU refuerza muro dominante`() {
        controller.reset()
        val walkable = WalkableCorridor(
            lateralOffsetNorm = 0f,
            confidence = 0.9f,
        )
        val layout = StreetLayoutState(alignment = SidewalkAlignment.ON_SIDEWALK)
        val corridor = CorridorState(
            leftProximity = 0.20f,
            rightProximity = 0.40f,
        )

        val signal = controller.compute(
            walkable = walkable,
            layout = layout,
            corridor = corridor,
            dangerLevel = SidewalkNotificationSystem.Level.OK,
            yawRateDegPerSec = 55f,
        )

        assertTrue(signal.rightBeep > 0.35f)
        assertEquals(0f, signal.leftBeep, 0.001f)
    }

    @Test
    fun `offset positivo genera pitido derecho`() {
        controller.reset()
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
    fun `offset negativo genera pitido izquierdo`() {
        controller.reset()
        val walkable = WalkableCorridor(
            lateralOffsetNorm = -0.45f,
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

        assertTrue(signal.leftBeep > 0.2f)
        assertEquals(0f, signal.rightBeep, 0.001f)
    }

    @Test
    fun `histeresis evita pitido al borde del deadband`() {
        controller.reset()
        val layout = StreetLayoutState(alignment = SidewalkAlignment.ON_SIDEWALK)
        val corridor = CorridorState()

        // Empezar centrado (silencio).
        controller.compute(
            walkable = WalkableCorridor(lateralOffsetNorm = 0.05f, confidence = 0.8f),
            layout = layout,
            corridor = corridor,
            dangerLevel = SidewalkNotificationSystem.Level.OK,
        )

        // Desvío leve dentro de EXIT_SILENCE: sigue en silencio.
        val stillSafe = controller.compute(
            walkable = WalkableCorridor(lateralOffsetNorm = 0.16f, confidence = 0.8f),
            layout = layout,
            corridor = corridor,
            dangerLevel = SidewalkNotificationSystem.Level.OK,
        )
        assertTrue(stillSafe.inSafeZone)

        // Cruzar EXIT_SILENCE: pita.
        val beeping = controller.compute(
            walkable = WalkableCorridor(lateralOffsetNorm = 0.22f, confidence = 0.8f),
            layout = layout,
            corridor = corridor,
            dangerLevel = SidewalkNotificationSystem.Level.OK,
        )
        assertFalse(beeping.inSafeZone)
        assertTrue(beeping.rightBeep > 0f)

        // Volver a 0.16: aún pita (no entra en silencio hasta ENTER_SILENCE).
        val stillBeeping = controller.compute(
            walkable = WalkableCorridor(lateralOffsetNorm = 0.16f, confidence = 0.8f),
            layout = layout,
            corridor = corridor,
            dangerLevel = SidewalkNotificationSystem.Level.OK,
        )
        assertFalse(stillBeeping.inSafeZone)

        // Por debajo de ENTER_SILENCE: silencio de nuevo.
        val quiet = controller.compute(
            walkable = WalkableCorridor(lateralOffsetNorm = 0.10f, confidence = 0.8f),
            layout = layout,
            corridor = corridor,
            dangerLevel = SidewalkNotificationSystem.Level.OK,
        )
        assertTrue(quiet.inSafeZone)
        assertEquals(0f, quiet.leftBeep, 0.001f)
        assertEquals(0f, quiet.rightBeep, 0.001f)
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

    @Test
    fun `frontal a 3m dispara warning insistente ambos oidos`() {
        val base = LateralGuidanceController.Signal(
            leftBeep = 0.4f,
            continuous = true,
            guidanceMode = true,
        )
        val boosted = controller.frontalBoost(
            signal = base,
            frontalDistanceM = 3.0f,
            frontalSeverity = 0f,
        )
        assertTrue(boosted.warning)
        assertTrue(boosted.leftBeep >= 0.85f)
        assertTrue(boosted.rightBeep >= 0.85f)
        assertTrue(boosted.continuous)
        assertFalse(boosted.inSafeZone)
    }

    @Test
    fun `severidad monocular alta equivale a alerta a 3m`() {
        val base = LateralGuidanceController.Signal(inSafeZone = true, guidanceMode = true)
        val boosted = controller.frontalBoost(
            signal = base,
            frontalDistanceM = null,
            frontalSeverity = LateralGuidanceController.FRONTAL_SEVERITY_INSISTENT,
        )
        assertTrue(boosted.warning)
        assertTrue(boosted.leftBeep >= 0.85f)
        assertTrue(boosted.rightBeep >= 0.85f)
    }

    @Test
    fun `frontal lejano no activa warning`() {
        val base = LateralGuidanceController.Signal(
            rightBeep = 0.3f,
            continuous = true,
            guidanceMode = true,
        )
        val boosted = controller.frontalBoost(
            signal = base,
            frontalDistanceM = 5.0f,
            frontalSeverity = 0.1f,
        )
        assertFalse(boosted.warning)
    }
}
