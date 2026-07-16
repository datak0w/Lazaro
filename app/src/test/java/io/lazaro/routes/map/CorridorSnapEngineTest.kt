package io.lazaro.routes.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CorridorSnapEngineTest {

    private val bundle = CorridorBundle(
        name = "test",
        path = listOf(
            CorridorPathPoint(36.5645, -4.8562),
            CorridorPathPoint(36.5650, -4.8555),
            CorridorPathPoint(36.5655, -4.8548),
        ),
        profile = listOf(
            CorridorProfilePoint(36.5645, -4.8562, 0f, 30f, 0f, 2f, "rural_lane"),
            CorridorProfilePoint(36.5655, -4.8548, 120f, 35f, 5f, 2.5f, "rural_lane"),
        ),
        nodes = listOf(
            CorridorNode(36.5650, -4.8555, 60f, CorridorNodeType.FORK, "Bifurcación"),
        ),
    )

    @Test
    fun snapOnPathReturnsHighScore() {
        val snap = CorridorSnapEngine.snap(36.5648, -4.8558, bundle, accuracyM = 8f)
        assertNotNull(snap)
        assertTrue(snap!!.onCorridor)
        assertTrue(snap.odmScore >= 0.5f)
    }

    @Test
    fun snapFarFromPathLowScore() {
        val snap = CorridorSnapEngine.snap(36.5700, -4.8500, bundle, accuracyM = 8f)
        assertNotNull(snap)
        assertTrue(!snap!!.onCorridor || snap.odmScore < 0.5f)
    }

    @Test
    fun nodeAheadFindsNearestNode() {
        val node = CorridorSnapEngine.nodeAhead(bundle, 50f, lookaheadM = 30f)
        assertNotNull(node)
        assertEquals(CorridorNodeType.FORK, node!!.type)
    }
}
