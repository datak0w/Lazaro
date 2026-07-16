package io.lazaro.pathguide

import org.junit.Assert.assertTrue
import org.junit.Test

class WalkableCorridorEstimatorTest {

    private val estimator = WalkableCorridorEstimator()

    @Test
    fun `detecta corredor central en imagen uniforme con bordes`() {
        val width = 320
        val height = 480
        val gray = ByteArray(width * height) { 120 }
        for (y in (height * 0.65f).toInt() until (height * 0.92f).toInt()) {
            for (x in 0 until width) {
                val edgeBoost = when {
                    x < width * 0.18f || x > width * 0.82f -> 80
                    else -> 0
                }
                gray[y * width + x] = (120 + edgeBoost).toByte()
            }
        }

        val layout = StreetLayoutState(
            alignment = SidewalkAlignment.ON_SIDEWALK,
            safeSide = RoadSide.LEFT,
        )
        val corridor = estimator.estimate(gray, width, height, layout)

        assertTrue(corridor.confidence > 0.2f)
        assertTrue(corridor.leftEdgeNorm < 0.45f)
        assertTrue(corridor.rightEdgeNorm > 0.55f)
        assertTrue(kotlin.math.abs(corridor.lateralOffsetNorm) < 0.35f)
    }
}
