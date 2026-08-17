package io.lazaro.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordDetectorTest {

    @Test
    fun wakeOnlyHasEmptyCommand() {
        val match = WakeWordDetector.parse("Lázaro")
        assertTrue(match.detected)
        assertEquals("", match.command)
    }

    @Test
    fun wakePlusCommandIsExtracted() {
        val match = WakeWordDetector.parse("Lázaro qué hora es")
        assertTrue(match.detected)
        assertEquals("que hora es", match.command)
    }

    @Test
    fun commandWithoutWakeIsNotDetected() {
        val match = WakeWordDetector.parse("qué hora es")
        assertFalse(match.detected)
        assertEquals("", match.command)
    }

    @Test
    fun sleepCommandAfterWakeIsExtracted() {
        val match = WakeWordDetector.parse("lazaro vete a dormir")
        assertTrue(match.detected)
        assertEquals("vete a dormir", match.command)
        assertTrue(io.lazaro.assistant.SleepModeController.matchesSleepCommand(match.command))
    }
}
