package io.lazaro.routes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RouteIntentDetectorTest {

    private val detector = RouteIntentDetector()

    @Test
    fun detectStartRecording() {
        assertEquals(RouteIntent.START_RECORDING, detector.detect("lazaro graba ruta a casa"))
    }

    @Test
    fun detectStopRecording() {
        assertEquals(RouteIntent.STOP_RECORDING, detector.detect("para de grabar"))
    }

    @Test
    fun detectListRoutes() {
        assertEquals(RouteIntent.LIST_ROUTES, detector.detect("que rutas tengo"))
    }

    @Test
    fun extractDestinationKeyCasa() {
        assertEquals("casa", detector.extractDestinationKey("graba ruta a casa"))
    }

    @Test
    fun noIntentForNavigation() {
        assertNull(detector.detect("llevarme al supermercado"))
    }

    @Test
    fun extractRouteName() {
        assertNotNull(detector.extractRouteName("graba ruta a casa"))
    }
}
