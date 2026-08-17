package io.lazaro.pathguide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PedestrianObjectMapperTest {

    @Test
    fun mapsCocoCategoriesToSpanish() {
        assertEquals("persona", PedestrianObjectMapper.spanishLabel("person"))
        assertEquals("coche", PedestrianObjectMapper.spanishLabel("car"))
        assertEquals("semáforo", PedestrianObjectMapper.spanishLabel("traffic light"))
        assertEquals("stop", PedestrianObjectMapper.spanishLabel("stop sign"))
        assertEquals("perro", PedestrianObjectMapper.spanishLabel("dog"))
    }

    @Test
    fun lateralityFromBoundingBox() {
        assertEquals(
            ObjectSide.LEFT,
            PedestrianObjectMapper.sideFromBox(0f, 0f, 40f, 80f, 100f, 100f),
        )
        assertEquals(
            ObjectSide.CENTER,
            PedestrianObjectMapper.sideFromBox(35f, 10f, 65f, 90f, 100f, 100f),
        )
        assertEquals(
            ObjectSide.RIGHT,
            PedestrianObjectMapper.sideFromBox(70f, 0f, 100f, 80f, 100f, 100f),
        )
    }

    @Test
    fun phrasesAreShortSpanish() {
        assertEquals(
            "persona a la izquierda",
            PedestrianObjectMapper.phrase("persona", ObjectSide.LEFT),
        )
        assertEquals(
            "coche delante",
            PedestrianObjectMapper.phrase("coche", ObjectSide.CENTER),
        )
        assertEquals(
            "banco delante",
            PedestrianObjectMapper.phrase("banco", ObjectSide.CENTER),
        )
    }

    @Test
    fun rejectsNoisyIndoorCategories() {
        assertEquals(null, PedestrianObjectMapper.spanishLabel("chair"))
        assertEquals(null, PedestrianObjectMapper.spanishLabel("bottle"))
        assertEquals(null, PedestrianObjectMapper.fromBox(
            "chair", 0.9f, 20f, 20f, 80f, 80f, 100f, 100f,
        ))
    }

    @Test
    fun pickPrimaryPrefersFrontalPerson() {
        val sideCar = PedestrianObjectMapper.fromBox(
            category = "car",
            score = 0.9f,
            left = 0f,
            top = 20f,
            right = 20f,
            bottom = 50f,
            imageWidth = 100f,
            imageHeight = 100f,
        )!!
        val frontalPerson = PedestrianObjectMapper.fromBox(
            category = "person",
            score = 0.7f,
            left = 30f,
            top = 10f,
            right = 70f,
            bottom = 95f,
            imageWidth = 100f,
            imageHeight = 100f,
        )!!
        val primary = PedestrianObjectMapper.pickPrimary(listOf(sideCar, frontalPerson))
        assertNotNull(primary)
        assertEquals("person", primary!!.category)
        assertTrue(primary.isFrontal)
        assertEquals("persona delante", primary.phrase)
    }

    @Test
    fun frontalBoostIncreasesWithArea() {
        val small = PedestrianObjectMapper.fromBox(
            "bicycle", 0.6f, 40f, 40f, 55f, 70f, 100f, 100f,
        )!!
        val large = PedestrianObjectMapper.fromBox(
            "car", 0.8f, 20f, 10f, 80f, 95f, 100f, 100f,
        )!!
        val smallBoost = PedestrianObjectMapper.frontalBeepBoost(listOf(small))
        val largeBoost = PedestrianObjectMapper.frontalBeepBoost(listOf(large))
        assertTrue(largeBoost > smallBoost)
        assertTrue(largeBoost >= 0.5f)
    }
}
