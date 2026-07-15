package io.lazaro.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SavedPlaceIntentDetectorTest {

    private val detector = SavedPlaceIntentDetector()

    @Test
    fun detectSave() {
        assertEquals(SavedPlaceIntent.SAVE, detector.detect("guarda sitio farmacia"))
    }

    @Test
    fun detectSavePosition() {
        assertEquals(SavedPlaceIntent.SAVE, detector.detect("guardar posicion panaderia"))
    }

    @Test
    fun detectList() {
        assertEquals(SavedPlaceIntent.LIST, detector.detect("mis sitios guardados"))
    }

    @Test
    fun detectDelete() {
        assertEquals(SavedPlaceIntent.DELETE, detector.detect("borra sitio farmacia"))
    }

    @Test
    fun extractPlaceName() {
        assertEquals("farmacia", detector.extractPlaceName("guarda sitio farmacia"))
    }

    @Test
    fun noIntentForNavigation() {
        assertNull(detector.detect("llevarme a casa"))
    }

    @Test
    fun saveWithoutNameReturnsNullName() {
        assertNull(detector.extractPlaceName("guarda sitio"))
    }
}
