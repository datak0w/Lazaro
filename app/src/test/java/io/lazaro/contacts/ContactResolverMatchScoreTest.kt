package io.lazaro.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactResolverMatchScoreTest {

    @Test
    fun potiDoesNotMatchPedroPInitial() {
        assertEquals(0, ContactResolver.matchScore("poti", "Pedro P"))
        assertEquals(0, ContactResolver.matchScore("poti", "Pedro P."))
        assertEquals(0, ContactResolver.matchScore("poti", "Paco"))
    }

    @Test
    fun potiMatchesPotiAndCloseNames() {
        assertEquals(100, ContactResolver.matchScore("poti", "Poti"))
        assertEquals(95, ContactResolver.matchScore("poti", "Poti García"))
        assertTrue(ContactResolver.matchScore("poti", "Potito") >= ContactResolver.MIN_SCORE)
        assertTrue(ContactResolver.matchScore("poti", "Potti") >= ContactResolver.MIN_SCORE)
    }

    @Test
    fun shortQueryStillMatchesFullFirstName() {
        assertTrue(ContactResolver.matchScore("mar", "María") >= ContactResolver.MIN_SCORE)
        assertTrue(ContactResolver.matchScore("juan", "Juan Pérez") >= 90)
    }

    @Test
    fun singleLetterQueryDoesNotMatchEverything() {
        assertEquals(0, ContactResolver.matchScore("p", "Pedro P"))
        assertEquals(0, ContactResolver.matchScore("a", "Ana López"))
    }
}
