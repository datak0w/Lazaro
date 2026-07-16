package io.lazaro.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class MicrophoneArbitratorTest {

    @Test
    fun acquireIsIdempotentDoesNotStackDepth() {
        val arb = MicrophoneArbitrator()
        var pauseCount = 0
        var resumeCount = 0
        arb.bind(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            onPausePassive = { pauseCount++ },
            onResumePassive = { resumeCount++ },
        )

        arb.acquireCommandCapture()
        arb.acquireCommandCapture()
        arb.acquireCommandCapture()
        assertEquals(1, arb.commandDepth())
        assertEquals(1, pauseCount)
        assertEquals(MicrophoneMode.COMMAND_CAPTURE, arb.currentMode())

        arb.releaseCommandCapture()
        assertEquals(0, arb.commandDepth())
        assertEquals(MicrophoneMode.PASSIVE_WAKE_WORD, arb.currentMode())
    }
}
