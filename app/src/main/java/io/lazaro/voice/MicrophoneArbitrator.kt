package io.lazaro.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

enum class MicrophoneMode {
    PASSIVE_WAKE_WORD,
    COMMAND_CAPTURE,
}

@Singleton
class MicrophoneArbitrator @Inject constructor() {

    private var mode = MicrophoneMode.PASSIVE_WAKE_WORD
    private var commandDepth = 0
    private var resumeJob: Job? = null
    private var scope: CoroutineScope? = null

    private var onPausePassive: (() -> Unit)? = null
    private var onResumePassive: (() -> Unit)? = null

    fun bind(
        scope: CoroutineScope,
        onPausePassive: () -> Unit,
        onResumePassive: () -> Unit,
    ) {
        this.scope = scope
        this.onPausePassive = onPausePassive
        this.onResumePassive = onResumePassive
    }

    fun currentMode(): MicrophoneMode = mode

    fun commandDepth(): Int = commandDepth

    fun forcePassiveMode() {
        resumeJob?.cancel()
        resumeJob = null
        commandDepth = 0
        if (mode != MicrophoneMode.PASSIVE_WAKE_WORD) {
            mode = MicrophoneMode.PASSIVE_WAKE_WORD
            onResumePassive?.invoke()
        }
    }

    fun acquireCommandCapture() {
        resumeJob?.cancel()
        resumeJob = null
        // Idempotente: un solo nivel de “sesión de comando” evita depth stuck
        // (pause + startDirectListening sin skipPause dejaba depth=2 y wake PAUSED).
        if (commandDepth > 0) {
            mode = MicrophoneMode.COMMAND_CAPTURE
            return
        }
        commandDepth = 1
        mode = MicrophoneMode.COMMAND_CAPTURE
        onPausePassive?.invoke()
    }

    fun releaseCommandCapture() {
        if (commandDepth == 0 && mode == MicrophoneMode.PASSIVE_WAKE_WORD) return
        commandDepth = 0
        mode = MicrophoneMode.PASSIVE_WAKE_WORD
        resumeJob?.cancel()
        resumeJob = scope?.launch {
            delay(RESUME_DELAY_MS)
            if (commandDepth == 0 && mode == MicrophoneMode.PASSIVE_WAKE_WORD) {
                onResumePassive?.invoke()
            }
        }
    }

    fun shutdown() {
        resumeJob?.cancel()
        resumeJob = null
        commandDepth = 0
        mode = MicrophoneMode.PASSIVE_WAKE_WORD
    }

    companion object {
        private const val RESUME_DELAY_MS = 400L
    }
}
