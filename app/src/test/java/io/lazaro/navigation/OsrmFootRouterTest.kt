package io.lazaro.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OsrmFootRouterTest {

    private val router = OsrmFootRouter()

    @Test
    fun parseStepsFromFixture() {
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
        assertEquals(
            BlindNavigationPhraseBuilder.Action.FORWARD,
            router.actionForStep(steps[0]),
        )
        assertEquals(
            BlindNavigationPhraseBuilder.Action.TURN_RIGHT,
            router.actionForStep(steps[1]),
        )
        assertEquals(
            BlindNavigationPhraseBuilder.Action.ARRIVE,
            router.actionForStep(steps[2]),
        )
    }
}
