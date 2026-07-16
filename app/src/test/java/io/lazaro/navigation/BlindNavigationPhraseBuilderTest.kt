package io.lazaro.navigation

import io.lazaro.pathguide.MapsInstructionType
import io.lazaro.pathguide.RoadSide
import io.lazaro.pathguide.SidewalkAlignment
import io.lazaro.pathguide.StreetLayoutState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlindNavigationPhraseBuilderTest {

    @Test
    fun primaryActionsAreClearSpanish() {
        assertEquals(
            "Camina hacia adelante.",
            BlindNavigationPhraseBuilder.primaryTip(BlindNavigationPhraseBuilder.Action.FORWARD),
        )
        assertEquals(
            "Gira a la izquierda.",
            BlindNavigationPhraseBuilder.primaryTip(BlindNavigationPhraseBuilder.Action.TURN_LEFT),
        )
        assertEquals(
            "Gira a la derecha.",
            BlindNavigationPhraseBuilder.primaryTip(BlindNavigationPhraseBuilder.Action.TURN_RIGHT),
        )
        assertEquals(
            "Da la vuelta.",
            BlindNavigationPhraseBuilder.primaryTip(BlindNavigationPhraseBuilder.Action.U_TURN),
        )
    }

    @Test
    fun mapsLeftTurnBecomesClearTip() {
        val tip = BlindNavigationPhraseBuilder.announceFromMaps(
            instruction = "En 30 m gira a la izquierda hacia Calle Real",
            type = MapsInstructionType.TURN,
            streetLayout = StreetLayoutState(
                roadSide = RoadSide.RIGHT,
                safeSide = RoadSide.LEFT,
                alignment = SidewalkAlignment.ON_SIDEWALK,
            ),
        )
        assertTrue(tip.startsWith("Gira a la izquierda."))
        assertTrue(tip.contains("30"))
        assertTrue(!tip.contains("acera", ignoreCase = true))
    }

    @Test
    fun uTurnPreferredOverLeftRight() {
        val action = BlindNavigationPhraseBuilder.actionFromMaps(
            "Gira a la izquierda para dar la vuelta",
            MapsInstructionType.TURN,
            MapsNavigationParser.turnSide("Gira a la izquierda para dar la vuelta"),
        )
        assertEquals(BlindNavigationPhraseBuilder.Action.U_TURN, action)
        assertEquals(TurnSide.U_TURN, MapsNavigationParser.turnSide("Da la vuelta en 50 m"))
    }

    @Test
    fun straightInstruction() {
        val action = BlindNavigationPhraseBuilder.actionFromMaps(
            "Sigue recto 200 m",
            MapsInstructionType.STRAIGHT,
        )
        assertEquals(BlindNavigationPhraseBuilder.Action.FORWARD, action)
        assertTrue(
            BlindNavigationPhraseBuilder.announceFromMaps("Sigue recto 200 m")
                .startsWith("Camina hacia adelante."),
        )
    }
}
