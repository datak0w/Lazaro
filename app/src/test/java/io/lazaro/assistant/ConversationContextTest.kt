package io.lazaro.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationContextTest {

    @Test
    fun keepsOnlyLastEightTurns() {
        val ctx = ConversationContext()
        repeat(10) { i ->
            ctx.recordTurn("u$i", "a$i", sessionMarker = "[nav→casa]")
        }
        val turns = ctx.recentTurns()
        assertEquals(8, turns.size)
        assertEquals("u2", turns.first().userMessage)
        assertEquals("u9", turns.last().userMessage)
        assertTrue(ctx.formatRecentHistory().contains("CONVERSACIÓN RECIENTE"))
    }
}

class ActiveSessionTrackerTest {

    @Test
    fun pauseAndResumeFlow() {
        val tracker = ActiveSessionTracker()
        tracker.start(ActiveSessionKind.NAVIGATION, "casa")
        assertFalse(tracker.isPausedForChat())
        tracker.pauseForChat()
        assertTrue(tracker.isPausedForChat())
        assertTrue(tracker.formatForPrompt().contains("pausada"))
        tracker.resumeFromChat()
        assertFalse(tracker.isPausedForChat())
        assertEquals("casa", tracker.snapshot()?.label)
        assertEquals("¿Seguimos hacia casa?", tracker.snapshot()?.resumeQuestion())
    }
}
