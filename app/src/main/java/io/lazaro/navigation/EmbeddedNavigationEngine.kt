package io.lazaro.navigation

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.lazaro.directions.DirectionsRepository
import io.lazaro.directions.Route
import io.lazaro.voice.TextToSpeechManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Motor de navegación embebida que reemplaza Google Maps externo.
 * Usa Directions API + FusedLocationProvider para guiar al usuario
 * únicamente por voz y vibración, sin necesidad de ver la pantalla.
 */
@Singleton
class EmbeddedNavigationEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val routeFollower: RouteFollower,
    private val directionsRepository: DirectionsRepository,
    private val textToSpeechManager: TextToSpeechManager,
    private val navigationAudioCoordinator: NavigationAudioCoordinator,
    private val mapsVisionFusionCoordinator: MapsVisionFusionCoordinator,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var eventJob: Job? = null

    /** Estado expuesto para observadores. */
    val state = routeFollower.state

    /**
     * Inicia navegación embebida a pie hacia un destino por nombre.
     * Devuelve true si se pudo calcular e iniciar la ruta.
     */
    suspend fun startWalkingNavigation(
        destination: String,
        label: String,
        originLat: Double? = null,
        originLng: Double? = null,
    ): Boolean {
        stopNavigation()

        textToSpeechManager.speak("Calculando ruta a $label a pie.")

        val started = routeFollower.startToDestination(
            destination = destination,
            label = label,
            originLat = originLat,
            originLng = originLng,
        )
        if (!started) return false

        startListeningEvents()
        return true
    }

    /**
     * Inicia navegación embebida usando una ruta ya resuelta (modo híbrido / rutas guardadas).
     */
    fun startWithRoute(route: Route, label: String) {
        stopNavigation()
        textToSpeechManager.speak("Iniciando ruta guardada hacia $label.")
        routeFollower.start(route, label)
        startListeningEvents()
    }

    /** Detiene la navegación embebida y limpia recursos. */
    fun stopNavigation() {
        eventJob?.cancel()
        eventJob = null
        routeFollower.stop()
        mapsVisionFusionCoordinator.reset()
    }

    fun isNavigating(): Boolean = routeFollower.isActive()

    private fun startListeningEvents() {
        eventJob?.cancel()
        eventJob = scope.launch {
            routeFollower.events.collectLatest { event ->
                when (event) {
                    is RouteEvent.Instruction -> onInstruction(event)
                    is RouteEvent.Approach -> onApproach(event)
                    is RouteEvent.Progress -> { /* opcional: anuncios periódicos de progreso */ }
                    RouteEvent.OffRoute -> {
                        textToSpeechManager.speak("Parece que te has desviado. Recalculando ruta.")
                    }
                    RouteEvent.Recalculating -> {
                        // Ya lo anuncia OffRoute; aquí podríamos poner sonido de espera
                        navigationAudioCoordinator.onMapsInstructionStarting("recalculando")
                    }
                    RouteEvent.Arrived -> {
                        val tip = BlindNavigationPhraseBuilder.announceFromMaps(
                            instruction = "Has llegado a tu destino.",
                            type = io.lazaro.pathguide.MapsInstructionType.ARRIVE,
                            streetLayout = navigationAudioCoordinator.lastStreetLayout(),
                        )
                        textToSpeechManager.speak(tip)
                        TurnHapticFeedback.pulseForInstruction(context, "llegada")
                        stopNavigation()
                    }
                    is RouteEvent.Error -> {
                        textToSpeechManager.speak("Error de navegación: ${event.message}")
                    }
                }
            }
        }
    }

    private suspend fun onInstruction(event: RouteEvent.Instruction) {
        navigationAudioCoordinator.onMapsInstructionStarting(event.instruction)

        // Clasificar tipo de instrucción para usar el mismo parser existente
        val type = MapsNavigationParser.classifyInstruction(event.instruction)
        mapsVisionFusionCoordinator.onMapsInstruction(type, event.instruction)

        val tip = BlindNavigationPhraseBuilder.announceFromMaps(
            instruction = event.instruction,
            type = type,
            streetLayout = navigationAudioCoordinator.lastStreetLayout(),
        )

        TurnHapticFeedback.pulseForInstruction(context, event.instruction)
        textToSpeechManager.speak(tip)

        navigationAudioCoordinator.onMapsInstructionFinished()
    }

    private fun onApproach(event: RouteEvent.Approach) {
        // Anuncio anticipado más suave: vibración corta + voz breve
        if (event.distanceMeters <= 20) {
            TurnHapticFeedback.pulseForInstruction(context, "aproximación")
        }
        // No siempre hablamos en approach para no saturar; el Instruction ya cubre el giro
    }
}
