package io.lazaro.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepModeControllerTest {

    @Test
    fun sleepPhrasesAreDetected() {
        assertTrue(SleepModeController.matchesSleepCommand("vete a dormir"))
        assertTrue(SleepModeController.matchesSleepCommand("Lázaro, ve a dormir"))
        assertTrue(SleepModeController.matchesSleepCommand("modo dormir"))
        assertTrue(SleepModeController.matchesSleepCommand("duerme"))
        assertTrue(SleepModeController.matchesSleepCommand("silencio total"))
    }

    @Test
    fun unrelatedSpeechIsNotSleep() {
        assertFalse(SleepModeController.matchesSleepCommand("qué hora es"))
        assertFalse(SleepModeController.matchesSleepCommand("llévame a casa"))
        assertFalse(SleepModeController.matchesSleepCommand("lazaro"))
        assertFalse(SleepModeController.matchesSleepCommand(""))
    }

    @Test
    fun wakeFromSleepNeedsLazaroAndDespierta() {
        assertTrue(SleepModeController.matchesWakeFromSleep("Lázaro despierta"))
        assertTrue(SleepModeController.matchesWakeFromSleep("lazaro despertar"))
        assertTrue(SleepModeController.matchesWakeFromSleep("lasaro despiértate"))
        assertFalse(SleepModeController.matchesWakeFromSleep("lazaro"))
        assertFalse(SleepModeController.matchesWakeFromSleep("despierta"))
        assertFalse(SleepModeController.matchesWakeFromSleep("qué hora es"))
    }
}
