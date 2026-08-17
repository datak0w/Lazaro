package io.lazaro.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmIntentDetectorTest {
    private val detector = AlarmIntentDetector()

    @Test
    fun setAlarmAtSevenThirty() {
        val intent = detector.detect("pon una alarma a las siete y media")
        assertTrue(intent is AlarmVoiceIntent.Set)
        val set = intent as AlarmVoiceIntent.Set
        assertEquals(7, set.time.hour)
        assertEquals(30, set.time.minute)
    }

    @Test
    fun setAlarmNumeric() {
        val intent = detector.detect("pon alarma a las 8:15")
        assertTrue(intent is AlarmVoiceIntent.Set)
        val set = intent as AlarmVoiceIntent.Set
        assertEquals(8, set.time.hour)
        assertEquals(15, set.time.minute)
    }

    @Test
    fun cancelNext() {
        val intent = detector.detect("cancela la alarma")
        assertTrue(intent is AlarmVoiceIntent.Cancel)
    }

    @Test
    fun changeAlarm() {
        val intent = detector.detect("cambia la alarma a las nueve")
        assertTrue(intent is AlarmVoiceIntent.Change)
        val change = intent as AlarmVoiceIntent.Change
        assertEquals(9, change.to.hour)
    }

    @Test
    fun listAlarms() {
        assertTrue(detector.detect("qué alarmas tengo") is AlarmVoiceIntent.List)
    }

    @Test
    fun ignoreNonAlarm() {
        assertNull(detector.detect("pon música rock"))
        assertNull(detector.detect("qué hora es"))
    }

    @Test
    fun relativeMinutes() {
        val intent = detector.detect("pon una alarma en 10 minutos")
        assertNotNull(intent)
        assertTrue(intent is AlarmVoiceIntent.Set)
    }
}
