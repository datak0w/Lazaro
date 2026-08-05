package io.lazaro.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeActionFormatTest {

    @Test
    fun formatHasNoPeriodSuffix() {
        val text = TimeAction(TimeIntentDetector()).formatCurrentTime()
        assertTrue(text.startsWith("Son las "))
        assertFalse(text.contains("de la mañana"))
        assertFalse(text.contains("de la tarde"))
        assertFalse(text.contains("de la noche"))
        val morningCount = Regex("de la mañana").findAll(text).count()
        val afternoonCount = Regex("de la tarde").findAll(text).count()
        assertTrue(morningCount == 0 && afternoonCount == 0)
    }
}
