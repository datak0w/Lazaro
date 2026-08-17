package io.lazaro.vision

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneLookIntentDetectorTest {
    private val detector = SceneLookIntentDetector()

    @Test
    fun detectsCommonPhrases() {
        assertTrue(detector.detect("dime qué ves"))
        assertTrue(detector.detect("qué hay delante"))
        assertTrue(detector.detect("mira al frente"))
        assertTrue(detector.detect("describe la escena"))
    }

    @Test
    fun ignoresUnrelated() {
        assertFalse(detector.detect("dónde estoy"))
        assertFalse(detector.detect("pon una alarma"))
        assertFalse(detector.detect("navega a casa"))
    }
}
