package io.lazaro.routes.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class OjenOdmBundleParseTest {

    @Test
    fun parseBundleFromGeoJsonAndProfile() {
        val pathJson =
            """{"type":"Feature","geometry":{"type":"LineString","coordinates":[[-4.8562,36.5645],[-4.8555,36.5650],[-4.8548,36.5655]]}}"""
        val profileJson = """
            [
              {"lat":36.5645,"lng":-4.8562,"alongM":0,"bearingDeg":30,"gradePct":0,"widthM":2,"segmentTag":"rural_lane"},
              {"lat":36.5655,"lng":-4.8548,"alongM":100,"bearingDeg":35,"gradePct":5,"widthM":2.5,"segmentTag":"rural_lane"}
            ]
        """.trimIndent()
        val path = OjenOdmBundle.parsePathGeoJson(pathJson)
        assertEquals(3, path.size)
        val bundle = OjenOdmBundle.parseBundle("test", pathJson, profileJson, null)
        assertNotNull(bundle)
        assertFalse(bundle!!.isEmpty)
        assertEquals(3, bundle.path.size)
        assertEquals(2, bundle.profile.size)
    }
}
