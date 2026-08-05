package io.lazaro.navigation

import io.lazaro.assistant.ActiveSessionKind
import io.lazaro.assistant.ActiveSessionTracker
import io.lazaro.pathguide.PathGuideController
import io.lazaro.pathguide.PathGuideMode
import io.lazaro.voice.TextToSpeechManager
import io.lazaro.voice.WakeWordController
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NavigationSessionManager @Inject constructor(
    private val navigationGuidanceMonitor: NavigationGuidanceMonitor,
    private val ownNavigationGuide: OwnNavigationGuide,
    private val pathGuideController: PathGuideController,
    private val textToSpeechManager: TextToSpeechManager,
    private val wakeWordController: WakeWordController,
    private val mapsSessionCloser: MapsSessionCloser,
    private val mapsVisionFusionCoordinator: MapsVisionFusionCoordinator,
    private val hybridNavigationCoordinator: io.lazaro.routes.HybridNavigationCoordinator,
    private val activeSessionTracker: ActiveSessionTracker,
) {
    private var currentTarget: NavigationTarget? = null

    fun isNavigationActive(): Boolean {
        val session = activeSessionTracker.snapshot()
        val sessionIsNav = session != null &&
            session.kind in setOf(ActiveSessionKind.NAVIGATION, ActiveSessionKind.ROUTE_REPLAY)
        return navigationGuidanceMonitor.isNavigationActive() ||
            pathGuideController.currentMode() == PathGuideMode.NAVEGACION ||
            pathGuideController.currentMode() == PathGuideMode.RUTA ||
            sessionIsNav
    }

    fun startSession(
        label: String = "destino",
        routeReplay: Boolean = false,
        target: NavigationTarget? = null,
    ) {
        val resolvedTarget = target?.copy(label = target.label.ifBlank { label })
            ?: NavigationTarget(label = label.ifBlank { "destino" })
        currentTarget = resolvedTarget
        navigationGuidanceMonitor.startNavigation()
        mapsVisionFusionCoordinator.reset()
        val kind = if (routeReplay || pathGuideController.currentMode() == PathGuideMode.RUTA) {
            ActiveSessionKind.ROUTE_REPLAY
        } else {
            ActiveSessionKind.NAVIGATION
        }
        val resolvedLabel = resolvedTarget.label.ifBlank {
            hybridNavigationCoordinator.state.value.routeName ?: "destino"
        }
        activeSessionTracker.start(kind, resolvedLabel)
        navigationGuidanceMonitor.setMapsSpeechMuted(false)
        if (!routeReplay && kind == ActiveSessionKind.NAVIGATION) {
            ownNavigationGuide.start(resolvedTarget.copy(label = resolvedLabel))
        }
    }

    /** Pausa anuncios de Maps para chat; no cierra la navegación. */
    fun pauseForChat() {
        if (!isNavigationActive() && !activeSessionTracker.hasActiveSession()) return
        activeSessionTracker.pauseForChat()
        navigationGuidanceMonitor.setMapsSpeechMuted(true)
        ownNavigationGuide.stop()
    }

    /** Reanuda anuncios de Maps tras el chat. */
    fun resumeFromChat(): String {
        val snap = activeSessionTracker.resumeFromChat()
            ?: return "No hay navegación en pausa."
        navigationGuidanceMonitor.setMapsSpeechMuted(false)
        if (!navigationGuidanceMonitor.isNavigationActive()) {
            navigationGuidanceMonitor.startNavigation()
        }
        currentTarget?.let { ownNavigationGuide.start(it.copy(label = snap.label)) }
        return "De acuerdo. Seguimos hacia ${snap.label}."
    }

    suspend fun endSession(speakConfirmation: Boolean = true) {
        ownNavigationGuide.stop()
        navigationGuidanceMonitor.stopNavigation()
        navigationGuidanceMonitor.setMapsSpeechMuted(false)
        textToSpeechManager.stop()
        val learnMsg = if (pathGuideController.currentMode() == PathGuideMode.RUTA) {
            hybridNavigationCoordinator.stop()
        } else {
            pathGuideController.stop()
            null
        }
        mapsVisionFusionCoordinator.reset()
        mapsSessionCloser.closeMapsNavigation()
        mapsSessionCloser.bringLazaroToFront()
        activeSessionTracker.clear()
        currentTarget = null
        if (speakConfirmation) {
            val base = "Navegación terminada."
            textToSpeechManager.speak(if (learnMsg != null) "$base $learnMsg" else base)
        } else if (learnMsg != null) {
            textToSpeechManager.speak(learnMsg)
        }
        wakeWordController.ensurePassiveListening()
    }
}
