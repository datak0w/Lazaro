package io.lazaro.pathguide

import io.lazaro.navigation.TurnSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JunctionBeepGuideTest {

    @Test
    fun `T izquierda prioriza pitido izquierdo`() {
        val corridor = CorridorState(leftProximity = 0.05f, rightProximity = 0.7f)
        val signal = JunctionBeepGuide.compute(
            junction = JunctionType.T_LEFT,
            corridor = corridor,
            mapsTurnSide = null,
        )

        assertNotNull(signal)
        assertTrue(signal!!.leftBeep > signal.rightBeep)
    }

    @Test
    fun `T ambos respeta giro Maps`() {
        val corridor = CorridorState(leftProximity = 0.2f, rightProximity = 0.2f)
        val signal = JunctionBeepGuide.compute(
            junction = JunctionType.T_BOTH,
            corridor = corridor,
            mapsTurnSide = TurnSide.RIGHT,
        )

        assertNotNull(signal)
        assertTrue(signal!!.rightBeep > signal.leftBeep)
    }

    @Test
    fun `sin bifurcacion no hay senal`() {
        val signal = JunctionBeepGuide.compute(
            junction = JunctionType.NONE,
            corridor = CorridorState(),
            mapsTurnSide = null,
        )
        assertEquals(null, signal)
    }
}
