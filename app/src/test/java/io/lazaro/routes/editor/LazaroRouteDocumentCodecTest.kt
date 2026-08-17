package io.lazaro.routes.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LazaroRouteDocumentCodecTest {

    @Test
    fun roundTripPreservesAnnouncementsAndSides() {
        val doc = LazaroRouteDocument(
            name = "Camino del bar",
            destinationLabel = "Bar Castillo",
            waypoints = listOf(
                LazaroRouteDocument.LatLngPoint(36.56, -4.85),
                LazaroRouteDocument.LatLngPoint(36.561, -4.851),
                LazaroRouteDocument.LatLngPoint(36.562, -4.852),
            ),
            sidewalkSides = listOf(
                LazaroRouteDocument.SidewalkSpan(0, 1, "LEFT"),
            ),
            crossings = listOf(
                LazaroRouteDocument.RouteCrossing(36.5615, -4.8515, "Paso de cebra"),
            ),
            announcements = listOf(
                LazaroRouteDocument.RouteAnnouncement(
                    lat = 36.5605,
                    lng = -4.8505,
                    text = "Estás pasando justo frente al cementerio. Continúa derecho.",
                    radiusM = 15f,
                    id = "cementerio",
                ),
            ),
        )
        val json = LazaroRouteDocumentCodec.toJson(doc)
        assertTrue(json.contains("lazaro-route"))
        val back = LazaroRouteDocumentCodec.fromJson(json)
        assertNotNull(back)
        assertEquals("Camino del bar", back!!.name)
        assertEquals(3, back.waypoints.size)
        assertEquals(1, back.announcements.size)
        assertTrue(back.announcements[0].text.contains("cementerio"))
        assertEquals("LEFT", back.sidewalkSides[0].side)

        val saved = LazaroRouteDocumentCodec.toSavedRoute(doc)
        assertTrue(saved.editorDocumentJson.contains("cementerio"))
        assertTrue(saved.canonicalPolyline.isNotBlank())
        assertTrue(saved.totalLengthM > 0f)
    }
}
