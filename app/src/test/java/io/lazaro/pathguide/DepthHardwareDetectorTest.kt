package io.lazaro.pathguide

import org.junit.Assert.assertEquals
import org.junit.Test

class DepthHardwareDetectorTest {

    @Test
    fun a34UsesMonocularEvenWithArcoreDepth() {
        val mode = DepthHardwareDetector.resolveMode(
            manufacturer = "samsung",
            model = "SM-A346B",
            arCoreDepthSupported = true,
            ldafLikely = false,
        )
        assertEquals(DepthGuidanceMode.MONOCULAR, mode)
    }

    @Test
    fun pixel9PrefersArcoreDepthWhenSupported() {
        val mode = DepthHardwareDetector.resolveMode(
            manufacturer = "Google",
            model = "Pixel 9",
            arCoreDepthSupported = true,
            ldafLikely = true,
        )
        assertEquals(DepthGuidanceMode.ARCORE_DEPTH, mode)
    }

    @Test
    fun pixelWithoutArcoreDepthFallsBackToLdaf() {
        val mode = DepthHardwareDetector.resolveMode(
            manufacturer = "Google",
            model = "Pixel 4a",
            arCoreDepthSupported = false,
            ldafLikely = true,
        )
        assertEquals(DepthGuidanceMode.LDAF_ONLY, mode)
    }

    @Test
    fun genericDeviceWithoutExtrasUsesMonocular() {
        val mode = DepthHardwareDetector.resolveMode(
            manufacturer = "Xiaomi",
            model = "Redmi Note 12",
            arCoreDepthSupported = false,
            ldafLikely = false,
        )
        assertEquals(DepthGuidanceMode.MONOCULAR, mode)
    }
}
