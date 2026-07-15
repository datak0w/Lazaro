package io.lazaro.routes

import io.lazaro.actions.NavigationAction
import io.lazaro.actions.LocationAction
import io.lazaro.actions.MapsLaunchDeferrer
import io.lazaro.navigation.NavigationAudioCoordinator
import io.lazaro.pathguide.PathGuideController
import io.lazaro.pathguide.PathGuideMode
import io.lazaro.routes.entity.SavedRoute
import io.lazaro.routes.replay.RouteMapMatcher
import io.lazaro.routes.replay.RouteReplayBrain
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class HybridNavState(
    val active: Boolean = false,
    val routeId: Long? = null,
    val routeName: String? = null,
    val replaySegmentActive: Boolean = false,
    val matchConfidence: Float = 0f,
    val lateralOffsetM: Float = 0f,
)

@Singleton
class HybridNavigationCoordinator @Inject constructor(
    private val pathGuideController: PathGuideController,
    private val routeReplayBrain: RouteReplayBrain,
    private val routeMapMatcher: RouteMapMatcher,
    private val routeRepository: RouteRepository,
    private val navigationAction: NavigationAction,
    private val locationAction: LocationAction,
    private val mapsLaunchDeferrer: MapsLaunchDeferrer,
    private val navigationAudioCoordinator: NavigationAudioCoordinator,
) {
    private val _state = MutableStateFlow(HybridNavState())
    val state: StateFlow<HybridNavState> = _state.asStateFlow()

    suspend fun start(route: SavedRoute, destinationLabel: String): Boolean {
        routeReplayBrain.loadRoute(route.id)
        val started = pathGuideController.start(PathGuideMode.RUTA, routeId = route.id)
        if (!started) return false

        val location = locationAction.getCurrentLocation()
        mapsLaunchDeferrer.defer {
            navigationAction.launchWalkingNavigation(
                destinationLabel,
                location?.latitude,
                location?.longitude,
            )
        }

        _state.value = HybridNavState(
            active = true,
            routeId = route.id,
            routeName = route.name,
        )
        return true
    }

    suspend fun stop() {
        routeReplayBrain.reset()
        pathGuideController.stop()
        navigationAudioCoordinator.setReplaySegmentActive(false)
        _state.value = HybridNavState()
    }

    fun updateReplayMetrics(matchConfidence: Float, lateralOffsetM: Float, inReplay: Boolean) {
        navigationAudioCoordinator.setReplaySegmentActive(inReplay)
        _state.value = _state.value.copy(
            replaySegmentActive = inReplay,
            matchConfidence = matchConfidence,
            lateralOffsetM = lateralOffsetM,
        )
    }

    fun isReplaySegmentActive(): Boolean = _state.value.replaySegmentActive

    fun activeRouteId(): Long? = _state.value.routeId
}
