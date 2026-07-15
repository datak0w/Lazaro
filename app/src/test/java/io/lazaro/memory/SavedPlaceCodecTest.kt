package io.lazaro.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SavedPlaceCodecTest {

    @Test
    fun encodeDecodeRoundTrip() {
        val encoded = SavedPlaceCodec.encode(36.56, -4.85, "Calle Mayor")
        val coords = SavedPlaceCodec.decode(encoded)
        assertNotNull(coords)
        assertEquals(36.56, coords!!.first, 0.0001)
        assertEquals(-4.85, coords.second, 0.0001)
        assertEquals("Calle Mayor", SavedPlaceCodec.decodeAddress(encoded))
    }

    @Test
    fun slugifyName() {
        assertEquals("farmacia_centro", SavedPlaceCodec.slugify("Farmacia Centro"))
    }

    @Test
    fun decodeInvalidReturnsNull() {
        assertNull(SavedPlaceCodec.decode("not-coords"))
    }
}
