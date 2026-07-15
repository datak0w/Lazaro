package io.lazaro.ui.pathguide

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.lazaro.pathguide.PathGuideController
import io.lazaro.pathguide.PathGuideDebugState
import io.lazaro.pathguide.PathGuideMode
import io.lazaro.voice.MicrophoneArbitrator
import io.lazaro.voice.WakeWordController
import io.lazaro.voice.WakeWordStatus
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PathGuideDebugViewModel @Inject constructor(
    private val pathGuideController: PathGuideController,
    wakeWordController: WakeWordController,
    private val microphoneArbitrator: MicrophoneArbitrator,
) : ViewModel() {

    val debugState: StateFlow<PathGuideDebugState?> = pathGuideController.debugState
    val mode: StateFlow<PathGuideMode> = pathGuideController.mode
    val wakeWordStatus: StateFlow<WakeWordStatus> = wakeWordController.status
    val microphoneCommandDepth: Int get() = microphoneArbitrator.commandDepth()

    private var startedDebugHere = false

    fun ensurePreview() {
        viewModelScope.launch {
            val current = pathGuideController.currentMode()
            if (current == PathGuideMode.OFF) {
                val started = pathGuideController.start(PathGuideMode.DEBUG)
                startedDebugHere = started
            }
        }
    }

    fun onLeave() {
        if (startedDebugHere && pathGuideController.currentMode() == PathGuideMode.DEBUG) {
            pathGuideController.stop()
        }
        startedDebugHere = false
    }

    override fun onCleared() {
        onLeave()
        super.onCleared()
    }
}
