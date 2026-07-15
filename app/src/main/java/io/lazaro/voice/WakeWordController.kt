package io.lazaro.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

enum class WakeWordStatus {
    OFF,
    STARTING,
    ACTIVE,
    PAUSED,
    ERROR,
}

@Singleton
class WakeWordController @Inject constructor(
    private val offlineWakeWordEngine: OfflineWakeWordEngine,
    private val microphoneArbitrator: MicrophoneArbitrator,
) {
    private val _status = MutableStateFlow(WakeWordStatus.OFF)
    val status: StateFlow<WakeWordStatus> = _status.asStateFlow()

    private var scope: CoroutineScope? = null
    private var onDetected: (() -> Unit)? = null
    private var modelPath: String? = null
    private var restartJob: Job? = null
    private var shouldRun = false

    fun bind(scope: CoroutineScope, onWakeWordDetected: () -> Unit) {
        this.scope = scope
        onDetected = onWakeWordDetected
        microphoneArbitrator.bind(
            scope = scope,
            onPausePassive = { pausePassive() },
            onResumePassive = { scope.launch { resumePassive() } },
        )
    }

    fun start() {
        shouldRun = true
        scope?.launch { startPassiveEngine() }
    }

    fun stop() {
        shouldRun = false
        restartJob?.cancel()
        restartJob = null
        offlineWakeWordEngine.stop()
        microphoneArbitrator.shutdown()
        _status.value = WakeWordStatus.OFF
    }

    fun pauseForCommand() {
        microphoneArbitrator.acquireCommandCapture()
    }

    fun releaseAfterCommand() {
        microphoneArbitrator.releaseCommandCapture()
    }

    fun ensurePassiveListening() {
        microphoneArbitrator.forcePassiveMode()
        if (!shouldRun) return
        restartJob?.cancel()
        scope?.launch {
            delay(ENSURE_PASSIVE_DELAY_MS)
            if (shouldRun && microphoneArbitrator.currentMode() == MicrophoneMode.PASSIVE_WAKE_WORD) {
                startPassiveEngine()
            }
        }
    }

    private fun pausePassive() {
        if (_status.value == WakeWordStatus.OFF) return
        offlineWakeWordEngine.stop()
        _status.value = WakeWordStatus.PAUSED
    }

    private suspend fun resumePassive() {
        if (!shouldRun || microphoneArbitrator.currentMode() != MicrophoneMode.PASSIVE_WAKE_WORD) return
        startPassiveEngine()
    }

    private suspend fun startPassiveEngine() {
        if (!shouldRun) return
        if (microphoneArbitrator.currentMode() != MicrophoneMode.PASSIVE_WAKE_WORD) return

        _status.value = WakeWordStatus.STARTING
        val path = modelPath ?: offlineWakeWordEngine.ensureModel().getOrElse { error ->
            _status.value = WakeWordStatus.ERROR
            scheduleRestart()
            return
        }
        modelPath = path

        offlineWakeWordEngine.start(
            modelPath = path,
            onDetected = { onDetected?.invoke() },
            onError = { message ->
                if (message.isNotBlank()) {
                    _status.value = WakeWordStatus.ERROR
                } else {
                    _status.value = WakeWordStatus.PAUSED
                }
                scheduleRestart()
            },
        )

        if (offlineWakeWordEngine.isRunning()) {
            _status.value = WakeWordStatus.ACTIVE
        }
    }

    private fun scheduleRestart() {
        if (!shouldRun) return
        restartJob?.cancel()
        restartJob = scope?.launch {
            delay(RESTART_DELAY_MS)
            if (shouldRun && microphoneArbitrator.currentMode() == MicrophoneMode.PASSIVE_WAKE_WORD) {
                startPassiveEngine()
            }
        }
    }

    companion object {
        private const val RESTART_DELAY_MS = 1_200L
        private const val ENSURE_PASSIVE_DELAY_MS = 600L
    }
}
