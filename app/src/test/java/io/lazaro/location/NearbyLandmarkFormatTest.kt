package io.lazaro.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyLandmarkSpeechTest {

    @Test
    fun format_oneLandmark() {
        val line = NearbyLandmarkSpeech.formatSpokenLine(
            listOf(NearbyLandmark("Bar Castillo Solis", 30, "saved")),
        )
        assertEquals(
            "Como referencia, estás a unos 30 metros de Bar Castillo Solis.",
            line,
        )
    }

    @Test
    fun format_twoLandmarks() {
        val line = NearbyLandmarkSpeech.formatSpokenLine(
            listOf(
                NearbyLandmark("Bar Castillo Solis", 30, "osm"),
                NearbyLandmark("Plaza Constitución", 80, "osm"),
            ),
        )!!
        assertTrue(line.contains("30 metros de Bar Castillo Solis"))
        assertTrue(line.contains("80 metros de Plaza Constitución"))
    }

    @Test
    fun roundDistance_nearby() {
        assertEquals(30, NearbyLandmarkSpeech.roundDistance(28))
        assertEquals(10, NearbyLandmarkSpeech.roundDistance(12))
    }
}
