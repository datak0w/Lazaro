package io.lazaro.routes.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CorridorFusionEngineTest {

    @Test
    fun withoutHeatmapOdmDominates() {
        val confidence = CorridorFusionEngine.computeConfidence(
            CorridorFusionEngine.FusionInput(
                corridorScore = 0.6f,
                gpsScore = 0.5f,
                heatConfidence = 0f,
                odmScore = 0.9f,
                hasHeatmap = false,
                onCorridor = true,
            ),
        )
        assertTrue(confidence >= 0.65f)
    }

    @Test
    fun withHeatmapHeatmapContributes() {
        val withHeat = CorridorFusionEngine.computeConfidence(
            CorridorFusionEngine.FusionInput(
                corridorScore = 0.7f,
                gpsScore = 0.6f,
                heatConfidence = 0.85f,
                odmScore = 0.7f,
                hasHeatmap = true,
                onCorridor = true,
            ),
        )
        val withoutHeat = CorridorFusionEngine.computeConfidence(
            CorridorFusionEngine.FusionInput(
                corridorScore = 0.7f,
                gpsScore = 0.6f,
                heatConfidence = 0f,
                odmScore = 0.7f,
                hasHeatmap = false,
                onCorridor = true,
            ),
        )
        assertTrue(withHeat >= withoutHeat)
    }

    @Test
    fun lowerReplayThresholdWhenOdmStrong() {
        assertEquals(0.50f, CorridorFusionEngine.replayThreshold(0.8f, true), 0.001f)
        assertEquals(0.55f, CorridorFusionEngine.replayThreshold(0.5f, false), 0.001f)
    }
}
