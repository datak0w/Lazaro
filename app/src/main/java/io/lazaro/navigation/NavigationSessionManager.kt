package io.lazaro.navigation

import io.lazaro.pathguide.PathGuideController
import io.lazaro.pathguide.PathGuideMode
import io.lazaro.voice.TextToSpeechManager
import io.lazaro.voice.WakeWordController
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NavigationSessionManager @Inject constructor(
    private val navigationGuidanceMonitor: NavigationGuidanceMonitor,
    private val pathGuideController: PathGuideController,
    private val textToSpeechManager: TextToSpeechManager,
    private val wakeWordController: WakeWordController,
    private val mapsSessionCloser: MapsSessionCloser,
    private val mapsVisionFusionCoordinator: MapsVisionFusionCoordinator,
) {
    fun isNavigationActive(): Boolean {
        return navigationGuidanceMonitor.isNavigationActive() ||
            pathGuideController.currentMode() == PathGuideMode.NAVEGACION ||
            pathGuideController.currentMode() == PathGuideMode.RUTA
    }

    fun startSession() {
        navigationGuidanceMonitor.startNavigation()
        mapsVisionFusionCoordinator.reset()
    }

    suspend fun endSession(speakConfirmation: Boolean = true) {
        navigationGuidanceMonitor.stopNavigation()
        textToSpeechManager.stop()
        pathGuideController.stop()
        mapsVisionFusionCoordinator.reset()
        mapsSessionCloser.closeMapsNavigation()
        mapsSessionCloser.bringLazaroToFront()
        if (speakConfirmation) {
            textToSpeechManager.speak("Navegación terminada.")
        }
        wakeWordController.ensurePassiveListening()
    }
}
