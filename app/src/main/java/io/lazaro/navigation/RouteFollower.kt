package io.lazaro.navigation

import android.location.Location
import io.lazaro.directions.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.*
import javax.inject.Inject
import javax.inject.Singleton

/** Estado de seguimiento de una ruta activa. */
data class RouteTrackingState(
    val active: Boolean = false,
    val routeName: String? = null,
    val totalSteps: Int = 0,
    val currentStepIndex: Int = 0,
    val distanceToNextManeuver: Int = 0,
    val totalRemainingDistanceM: Int = 0,
    val totalRemainingDurationSec: Int = 0,
    val offRoute: Boolean = false,
    val arrived: Boolean = false,
    val error: String? = null,
)

/** Evento emitido cuando el usuario debe actuar o recibir información. */
sealed class RouteEvent {
    data class Instruction(
        val stepIndex: Int,
        val instruction: String,
        val maneuver: String? = null,
        val distanceMeters: Int,
    ) : RouteEvent()

    data class Approach(
        val stepIndex: Int,
        val instruction: String,
        val distanceMeters: Int,
    ) : RouteEvent()

    data class Progress(
        val totalSteps: Int,
        val currentStep: Int,
        val remainingDistanceM: Int,
        val remainingDurationSec: Int,
    ) : RouteEvent()

    data object OffRoute : RouteEvent()
    data object Recalculating : RouteEvent()
    data object Arrived : RouteEvent()
    data class Error(val message: String) : RouteEvent()
}

@Singleton
class RouteFollower @Inject constructor(
    private val gpsLocationProvider: GpsLocationProvider,
    private val directionsRepository: io.lazaro.directions.DirectionsRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val _state = MutableStateFlow(RouteTrackingState())
    val state: StateFlow<RouteTrackingState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<RouteEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<RouteEvent> = _events.asSharedFlow()

    private var currentRoute: Route? = null
    private var destinationLabel: String = "destino"
    private var destinationString: String = ""

    /** Inicia seguimiento de una ruta ya resuelta por Directions API. */
    fun start(route: Route, label: String) {
        stop()
        currentRoute = route
        destinationLabel = label

        val leg = route.legs.firstOrNull() ?: run {
            _events.tryEmit(RouteEvent.Error("Ruta sin tramos."))
            return
        }
        val steps = leg.steps.filter { it.travelMode == "WALKING" || it.travelMode == null }
        if (steps.isEmpty()) {
            _events.tryEmit(RouteEvent.Error("Ruta sin pasos a pie."))
            return
        }

        _state.value = RouteTrackingState(
            active = true,
            routeName = label,
            totalSteps = steps.size,
            currentStepIndex = 0,
            totalRemainingDistanceM = leg.distance?.value ?: 0,
            totalRemainingDurationSec = leg.duration?.value ?: 0,
        )

        job = scope.launch {
            gpsLocationProvider.locationUpdates().collect { location ->
                onLocationUpdate(location, steps)
            }
        }
    }

    /** Inicia seguimiento buscando ruta desde coordenadas actuales hasta destino. */
    suspend fun startToDestination(
        destination: String,
        label: String,
        originLat: Double? = null,
        originLng: Double? = null,
    ): Boolean {
        stop()
        destinationString = destination
        destinationLabel = label

        val origin = if (originLat != null && originLng != null) {
            "$originLat,$originLng"
        } else {
            val loc = gpsLocationProvider.getCurrentLocation()
            if (loc == null) {
                _events.tryEmit(RouteEvent.Error("No pude obtener tu ubicación para calcular la ruta."))
                return false
            }
            "${loc.latitude},${loc.longitude}"
        }

        val result = directionsRepository.getWalkingRoute(origin, destination)
        val route = result.getOrElse { e ->
            _events.tryEmit(RouteEvent.Error("Error calculando ruta: ${e.localizedMessage ?: "desconocido"}"))
            return false
        }

        start(route, label)
        return true
    }

    fun stop() {
        job?.cancel()
        job = null
        currentRoute = null
        _state.value = RouteTrackingState()
    }

    fun isActive(): Boolean = _state.value.active

    private suspend fun onLocationUpdate(location: Location, steps: List<Step>) {
        val state = _state.value
        if (!state.active || state.arrived) return

        val stepIndex = state.currentStepIndex.coerceIn(0, steps.lastIndex)
        val currentStep = steps[stepIndex]

        // 1. Calcular distancia al punto final del step actual
        val endLoc = currentStep.endLocation ?: return
        val distToEnd = distanceMeters(
            location.latitude, location.longitude,
            endLoc.lat, endLoc.lng,
        )

        // 2. Actualizar estado con distancia al siguiente maniobra
        _state.value = state.copy(distanceToNextManeuver = distToEnd.toInt())

        // 3. Detectar si hemos llegado al destino final
        val isLastStep = stepIndex >= steps.lastIndex
        if (isLastStep && distToEnd < ARRIVAL_THRESHOLD_METERS) {
            _state.value = _state.value.copy(arrived = true, active = false)
            _events.emit(RouteEvent.Arrived)
            stop()
            return
        }

        // 4. Detectar proximidad al maniobra actual (anunciar anticipación)
        if (distToEnd < APPROACH_THRESHOLD_METERS && !_state.value.offRoute) {
            val instruction = if (isLastStep) {
                "Estás a ${distToEnd.toInt()} metros de $destinationLabel."
            } else {
                val nextStep = steps.getOrNull(stepIndex + 1)
                val nextInstr = nextStep?.cleanInstruction() ?: "continúa"
                "En ${distToEnd.toInt()} metros, $nextInstr"
            }
            _events.emit(
                RouteEvent.Approach(
                    stepIndex = stepIndex,
                    instruction = instruction,
                    distanceMeters = distToEnd.toInt(),
                ),
            )
        }

        // 5. Si cruzamos el umbral del step actual, avanzar al siguiente
        if (distToEnd < STEP_COMPLETE_THRESHOLD_METERS && !isLastStep) {
            val nextIndex = stepIndex + 1
            val nextStep = steps[nextIndex]
            val cleaned = nextStep.cleanInstruction()
            val maneuver = nextStep.maneuver
            val dist = nextStep.distance?.value ?: 0

            _state.value = _state.value.copy(currentStepIndex = nextIndex)
            _events.emit(
                RouteEvent.Instruction(
                    stepIndex = nextIndex,
                    instruction = cleaned,
                    maneuver = maneuver,
                    distanceMeters = dist,
                ),
            )
        }

        // 6. Detectar desvío de ruta
        val nearestDist = nearestDistanceToRoute(location, steps)
        if (nearestDist > OFF_ROUTE_THRESHOLD_METERS) {
            if (!_state.value.offRoute) {
                _state.value = _state.value.copy(offRoute = true)
                _events.emit(RouteEvent.OffRoute)
            }
            // Auto-recalcular
            recalculateRoute(location, destinationString, destinationLabel)
        } else if (_state.value.offRoute && nearestDist < OFF_ROUTE_RECOVERED_METERS) {
            _state.value = _state.value.copy(offRoute = false)
        }

        // 7. Emitir progreso ocasional (cada step o cada cierta distancia)
        val remainingDist = calculateRemainingDistance(steps, stepIndex, location)
        val remainingDur = calculateRemainingDuration(steps, stepIndex)
        if (stepIndex != state.currentStepIndex || distToEnd < 50) {
            _events.emit(
                RouteEvent.Progress(
                    totalSteps = steps.size,
                    currentStep = stepIndex,
                    remainingDistanceM = remainingDist,
                    remainingDurationSec = remainingDur,
                ),
            )
        }
    }

    private suspend fun recalculateRoute(location: Location, destination: String, label: String) {
        _events.emit(RouteEvent.Recalculating)
        val result = directionsRepository.getWalkingRouteFromCoords(
            location.latitude, location.longitude, destination,
        )
        val route = result.getOrElse {
            _events.tryEmit(RouteEvent.Error("No pude recalcular la ruta. Sigue recto y lo intentaré de nuevo."))
            return
        }
        start(route, label)
    }

    private fun nearestDistanceToRoute(location: Location, steps: List<Step>): Double {
        var minDist = Double.MAX_VALUE
        for (step in steps) {
            val start = step.startLocation
            val end = step.endLocation
            if (start != null && end != null) {
                val dist = pointToSegmentDistance(
                    location.latitude, location.longitude,
                    start.lat, start.lng,
                    end.lat, end.lng,
                )
                if (dist < minDist) minDist = dist
            }
        }
        return minDist
    }

    private fun calculateRemainingDistance(steps: List<Step>, fromIndex: Int, currentLoc: Location): Int {
        var sum = 0
        for (i in fromIndex until steps.size) {
            sum += steps[i].distance?.value ?: 0
        }
        // Ajustar con distancia real al final del step actual
        val currentStep = steps.getOrNull(fromIndex)
        val end = currentStep?.endLocation
        if (end != null) {
            val realDist = distanceMeters(currentLoc.latitude, currentLoc.longitude, end.lat, end.lng)
            sum = (sum - (currentStep.distance?.value ?: 0) + realDist.toInt()).coerceAtLeast(0)
        }
        return sum
    }

    private fun calculateRemainingDuration(steps: List<Step>, fromIndex: Int): Int {
        var sum = 0
        for (i in fromIndex until steps.size) {
            sum += steps[i].duration?.value ?: 0
        }
        return sum
    }

    companion object {
        /** Umbral para considerar que llegamos al final de un step (metros). */
        private const val STEP_COMPLETE_THRESHOLD_METERS = 15.0

        /** Umbral para anunciar aproximación al siguiente maniobra (metros). */
        private const val APPROACH_THRESHOLD_METERS = 35.0

        /** Umbral para considerar llegada al destino final (metros). */
        private const val ARRIVAL_THRESHOLD_METERS = 20.0

        /** Umbral para detectar desvío de ruta (metros). */
        private const val OFF_ROUTE_THRESHOLD_METERS = 80.0

        /** Umbral para considerar que volvimos a la ruta tras un desvío (metros). */
        private const val OFF_ROUTE_RECOVERED_METERS = 40.0
    }
}

/** Distancia Haversine entre dos puntos en metros. */
fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val r = 6_371_000.0 // radio de la Tierra en metros
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2).pow(2.0) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2.0)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return r * c
}

/** Distancia perpendicular de un punto al segmento entre A y B (metros). */
fun pointToSegmentDistance(
    px: Double, py: Double,
    ax: Double, ay: Double,
    bx: Double, by: Double,
): Double {
    val abx = bx - ax
    val aby = by - ay
    val apx = px - ax
    val apy = py - ay
    val ab2 = abx * abx + aby * aby
    if (ab2 == 0.0) return distanceMeters(px, py, ax, ay)
    val t = ((apx * abx + apy * aby) / ab2).coerceIn(0.0, 1.0)
    val cx = ax + t * abx
    val cy = ay + t * aby
    return distanceMeters(px, py, cx, cy)
}
