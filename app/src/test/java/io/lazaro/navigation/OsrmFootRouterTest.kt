package io.lazaro.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OsrmFootRouterTest {

    private val router = OsrmFootRouter()

    @Test
    fun parseRoutePlanFromFixture() {
        val json = """
            {
              "code":"Ok",
              "routes":[{
                "distance":70.0,
                "duration":90.0,
                "geometry":{"type":"LineString","coordinates":[[-4.42,36.718],[-4.419,36.718],[-4.419,36.717]]},
                "legs":[{
                  "steps":[
                    {"distance":40.0,"duration":50.0,"name":"Calle Mayor","maneuver":{"type":"depart","modifier":"straight","bearing_after":90,"location":[-4.42,36.718]},"geometry":{"type":"LineString","coordinates":[[-4.42,36.718],[-4.419,36.718]]}},
                    {"distance":25.0,"duration":30.0,"name":"Calle Luna","maneuver":{"type":"turn","modifier":"right","bearing_after":180,"location":[-4.419,36.718]},"geometry":{"type":"LineString","coordinates":[[-4.419,36.718],[-4.419,36.717]]}},
                    {"distance":5.0,"duration":10.0,"name":"","maneuver":{"type":"arrive","location":[-4.419,36.717]}}
                  ]
                }]
              }]
            }
        """.trimIndent()
        val plan = router.parseRoutePlan(json)
        assertNotNull(plan)
        assertEquals(3, plan!!.steps.size)
        assertEquals(70.0, plan.totalDistanceM, 0.01)
        assertEquals(90.0, plan.totalDurationS, 0.01)
        assertTrue(plan.polyline.size >= 2)
        assertEquals("Calle Mayor", plan.steps[0].name)
        assertEquals(0.0, plan.steps[0].startAlongM, 0.01)
        assertEquals(40.0, plan.steps[1].startAlongM, 0.01)
        assertEquals(
            BlindNavigationPhraseBuilder.Action.FORWARD,
            router.actionForStep(plan.steps[0]),
        )
        assertEquals(
            BlindNavigationPhraseBuilder.Action.TURN_RIGHT,
            router.actionForStep(plan.steps[1]),
        )
        assertEquals(
            BlindNavigationPhraseBuilder.Action.ARRIVE,
            router.actionForStep(plan.steps[2]),
        )
    }

    @Test
    fun parseStepsCompat() {
        val json = """
            {
              "code":"Ok",
              "routes":[{
                "legs":[{
                  "steps":[
                    {"distance":40.0,"maneuver":{"type":"depart","modifier":"straight","bearing_after":90,"location":[-4.42,36.718]}},
                    {"distance":25.0,"maneuver":{"type":"turn","modifier":"right","bearing_after":180,"location":[-4.419,36.718]}},
                    {"distance":5.0,"maneuver":{"type":"arrive","location":[-4.419,36.717]}}
                  ]
                }]
              }]
            }
        """.trimIndent()
        val steps = router.parseSteps(json)
        assertNotNull(steps)
        assertEquals(3, steps!!.size)
    }

    @Test
    fun polylineProgressTracksAlong() {
        val tracker = PolylineProgressTracker()
        tracker.load(
            listOf(
                OsrmLatLng(36.718, -4.42),
                OsrmLatLng(36.718, -4.419),
                OsrmLatLng(36.717, -4.419),
            ),
            totalDistanceM = 150.0,
        )
        val mid = tracker.update(36.718, -4.4195)
        assertTrue(mid.alongM > 0)
        assertTrue(mid.remainingM < 150.0)
        assertTrue(!mid.offRoute)
    }
}
