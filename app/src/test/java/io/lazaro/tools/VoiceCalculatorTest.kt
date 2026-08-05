package io.lazaro.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCalculatorTest {

    @Test
    fun digitsMultiplication() {
        assertEquals("Es 666.", VoiceCalculator.tryEvaluate("cuánto es 18 por 37"))
    }

    @Test
    fun spokenNumbersMultiplication() {
        val r = VoiceCalculator.tryEvaluate("cuanto es dieciocho por treinta y siete")
        assertEquals("Es 666.", r)
    }

    @Test
    fun spokenAddition() {
        assertEquals("Es 45.", VoiceCalculator.tryEvaluate("cuanto son veinte mas veinticinco"))
    }

    @Test
    fun divisionWords() {
        assertEquals("Es 6.", VoiceCalculator.tryEvaluate("cuanto es treinta dividido entre cinco"))
    }

    @Test
    fun fiveAndThreeIsAddition() {
        assertEquals("Es 8.", VoiceCalculator.tryEvaluate("cuanto es cinco y tres"))
    }

    @Test
    fun thirtySevenCompoundInProduct() {
        assertEquals("Es 666.", VoiceCalculator.tryEvaluate("cuanto es dieciocho por treinta y siete"))
    }

    @Test
    fun detectorCatchesSpoken() {
        val d = CalculatorIntentDetector()
        assertTrue(d.detect("Lázaro cuánto es dieciocho por treinta y siete"))
        assertNotNull(VoiceCalculator.tryEvaluate("Lázaro cuánto es dieciocho por treinta y siete"))
    }
}
