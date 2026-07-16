package io.lazaro.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextIntentDetectorTest {

    private val detector = ContextIntentDetector()

    @Test
    fun shortParaIsInterrupt() {
        assertTrue(detector.isInterruptCommand("para"))
        assertTrue(detector.isInterruptCommand("Lázaro, para"))
        assertTrue(detector.isInterruptCommand("parar"))
        assertTrue(detector.isInterruptCommand("para ya"))
        assertTrue(detector.isInterruptCommand("detente"))
        assertTrue(detector.isInterruptCommand("callate"))
    }

    @Test
    fun commandsWithParaWordAreNotInterrupt() {
        assertFalse(detector.isInterruptCommand("llévame para casa"))
        assertFalse(detector.isInterruptCommand("navega para el médico"))
        assertFalse(detector.isInterruptCommand("prepárate"))
        assertFalse(detector.isInterruptCommand("separar la llamada"))
        assertFalse(detector.isInterruptCommand("ir para la plaza"))
    }

    @Test
    fun navigationStopPhrases() {
        assertTrue(detector.isNavigationStopPhrase("terminar navegación"))
        assertTrue(detector.isNavigationStopPhrase("cerrar maps"))
        assertTrue(detector.isNavigationStopPhrase("terminar paseo"))
    }
}
