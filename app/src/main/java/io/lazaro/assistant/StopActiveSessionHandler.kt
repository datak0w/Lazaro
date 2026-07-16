package io.lazaro.assistant

import io.lazaro.actions.ActionExecutor
import io.lazaro.actions.ActionResult
import io.lazaro.audiobook.BookReaderAction
import io.lazaro.navigation.NavigationSessionManager
import io.lazaro.pathguide.PathGuideController
import io.lazaro.pathguide.PathGuideMode
import io.lazaro.pathguide.WalkModeAction
import io.lazaro.routes.recording.RouteRecorderController
import io.lazaro.voice.TextToSpeechManager
import javax.inject.Inject
import javax.inject.Singleton

sealed class StopSessionResult {
    data object NotHandled : StopSessionResult()
    data class Handled(val spokeMessage: Boolean = false) : StopSessionResult()
}

@Singleton
class StopActiveSessionHandler @Inject constructor(
    private val contextIntentDetector: ContextIntentDetector,
    private val navigationSessionManager: NavigationSessionManager,
    private val pathGuideController: PathGuideController,
    private val walkModeAction: WalkModeAction,
    private val bookReaderAction: BookReaderAction,
    private val actionExecutor: ActionExecutor,
    private val routeRecorderController: RouteRecorderController,
    private val textToSpeechManager: TextToSpeechManager,
) {
    fun shouldHandleStop(text: String): Boolean {
        return contextIntentDetector.isInterruptCommand(text) ||
            contextIntentDetector.isNavigationStopPhrase(text)
    }

    suspend fun handleStop(text: String): StopSessionResult {
        if (!shouldHandleStop(text)) return StopSessionResult.NotHandled

        if (navigationSessionManager.isNavigationActive()) {
            textToSpeechManager.stop()
            navigationSessionManager.endSession(speakConfirmation = true)
            return StopSessionResult.Handled(spokeMessage = true)
        }

        if (routeRecorderController.isRecording()) {
            textToSpeechManager.stop()
            when (val result = routeRecorderController.stopRecording()) {
                is ActionResult.Success -> textToSpeechManager.speak(result.message)
                is ActionResult.Error -> textToSpeechManager.speak(result.message)
                is ActionResult.NeedsConfirmation -> textToSpeechManager.speak(result.prompt)
            }
            return StopSessionResult.Handled(spokeMessage = true)
        }

        if (pathGuideController.currentMode() == PathGuideMode.PASEO) {
            textToSpeechManager.stop()
            val result = walkModeAction.stop()
            if (result is ActionResult.Success) {
                textToSpeechManager.speak(result.message)
            }
            return StopSessionResult.Handled(spokeMessage = result is ActionResult.Success)
        }

        if (actionExecutor.hasPendingConfirmation()) {
            textToSpeechManager.stop()
            when (val result = actionExecutor.cancelPending()) {
                is ActionResult.Success -> textToSpeechManager.speak(result.message)
                is ActionResult.Error -> textToSpeechManager.speak(result.message)
                is ActionResult.NeedsConfirmation -> textToSpeechManager.speak(result.prompt)
            }
            return StopSessionResult.Handled(spokeMessage = true)
        }

        // «Para» sin sesión activa: callar; el caller vuelve a standby en silencio.
        textToSpeechManager.stop()
        bookReaderAction.stopPlayback()
        if (pathGuideController.isActive()) {
            pathGuideController.stop()
        }
        return StopSessionResult.Handled(spokeMessage = false)
    }
}
