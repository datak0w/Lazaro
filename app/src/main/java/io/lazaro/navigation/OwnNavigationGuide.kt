package io.lazaro.navigation

import android.content.Context
import android.location.Geocoder
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.lazaro.actions.LocationAction
import io.lazaro.memory.SavedPlaceRepository
import io.lazaro.pathguide.DeviceRotationTracker
import io.lazaro.pathguide.MapsInstructionType
import io.lazaro.routes.location.HighAccuracyLocationProvider
import io.lazaro.routes.map.OjenMapBundle
import io.lazaro.voice.TextToSpeechManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Guía hablada propia con Directions (Google) o OSRM:
 * anticipa giros y recalcula si te sales de la cinta. Sin app Google Maps.
 */
@Singleton
class OwnNavigationGuide @Inject constructor(
    @ApplicationContext private val context: Context,
    private val locationAction: LocationAction,
    private val highAccuracyLocationProvider: HighAccuracyLocationProvider,
    private val deviceRotationTracker: DeviceRotationTracker,
    private val osrmFootRouter: OsrmFootRouter,
    private val googleDirectionsRouter: GoogleDirectionsRouter,
    private val textToSpeechManager: TextToSpeechManager,
    private val navigationGuidanceMonitor: NavigationGuidanceMonitor,
    private val audioCoordinator: NavigationAudioCoordinator,
    private val mapsVisionFusionCoordinator: MapsVisionFusionCoordinator,
    private val savedPlaceRepository: SavedPlaceRepository,
    private val ojenMapBundle: OjenMapBundle,
    private val sleepModeController: io.lazaro.assistant.SleepModeController,
    private val streetSidePreferenceRepository: StreetSidePreferenceRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val active = AtomicBoolean(false)
    private var loopJob: Job? = null
    private var gpsJob: Job? = null
    private var target: NavigationTarget? = null
    private var destLat: Double? = null
    private var destLng: Double? = null
    private var routePlan: OsrmRoutePlan? = null
    private var steps: List<OsrmStep> = emptyList()
    private var stepIndex = 0
    private val progressTracker = PolylineProgressTracker()
    private var lastProgress: PolylineProgress? = null
    private var lastOwnTip: String? = null
    private var lastOwnTipMs = 0L
    private var announcedInitial = false
    private var announcedMilestones: MutableSet<Int> = mutableSetOf()
    private var announcedEtaMilestones: MutableSet<Int> = mutableSetOf()
    /** stepIndex → umbrales ya anunciados (80 / 30 / 15). */
    private val announcedManeuverBands = mutableMapOf<Int, MutableSet<Int>>()
    private var lastLandmarkKey: String? = null
    private var lastLandmarkMs = 0L
    private var lastRecalcMs = 0L
    private var lastCrossingTipMs = 0L
    private var lastOwnTurnWindowStep = -1
    private var cachedAction: BlindNavigationPhraseBuilder.Action? = null
    private var cachedDistanceM: Int? = null
    private var cachedEtaMinutes: Int? = null
    private var cachedStreet: String? = null
    private var cachedMetersToManeuver: Int? = null
    private var cachedNextManeuver: String? = null
    private var cachedOffRoute = false
    private var lastStreetSideTipMs = 0L
    private var lastStreetSideKey: String? = null
    /** Fuente del plan actual: google | osrm | none */
    private var routeSource: String = "none"
    private var lastContinueStraightMs = 0L
    private var continueStraightVariation = 0
    private var lastHeadingCorrectMs = 0L
    private var lastAnnouncedStreet: String? = null
    private var lastSpokenManeuverIdx = -1
    private var afterTurnPending = false

    fun start(navigationTarget: NavigationTarget) {
        stop()
        target = navigationTarget
        destLat = navigationTarget.latitude
        destLng = navigationTarget.longitude
        resetRouteState()
        active.set(true)
        deviceRotationTracker.start()
        bindGpsStream()
        loopJob = scope.launch { runGuideLoop(announceInitial = true) }
    }

    fun pauseTips() {
        active.set(false)
        loopJob?.cancel()
        loopJob = null
    }

    fun resumeTips() {
        if (target == null) return
        if (active.get() && loopJob?.isActive == true) return
        active.set(true)
        deviceRotationTracker.start()
        bindGpsStream()
        loopJob = scope.launch { runGuideLoop(announceInitial = false) }
    }

    fun stop() {
        active.set(false)
        loopJob?.cancel()
        loopJob = null
        gpsJob?.cancel()
        gpsJob = null
        target = null
        destLat = null
        destLng = null
        resetRouteState()
    }

    private fun bindGpsStream() {
        if (gpsJob?.isActive == true) return
        gpsJob = highAccuracyLocationProvider.fixes(intervalMs = 1_000L)
            .onEach { /* alimenta lastGoodFix vía provider */ }
            .launchIn(scope)
    }

    private fun resetRouteState() {
        routePlan = null
        steps = emptyList()
        stepIndex = 0
        progressTracker.clear()
        lastProgress = null
        announcedInitial = false
        announcedMilestones = mutableSetOf()
        announcedEtaMilestones = mutableSetOf()
        announcedManeuverBands.clear()
        lastLandmarkKey = null
        lastOwnTurnWindowStep = -1
        cachedAction = null
        cachedDistanceM = null
        cachedEtaMinutes = null
        cachedStreet = null
        cachedMetersToManeuver = null
        cachedNextManeuver = null
        cachedOffRoute = false
        lastContinueStraightMs = 0L
        continueStraightVariation = 0
        lastHeadingCorrectMs = 0L
        lastAnnouncedStreet = null
        lastSpokenManeuverIdx = -1
        afterTurnPending = false
        lastCrossingTipMs = 0L
        lastStreetSideTipMs = 0L
        lastStreetSideKey = null
    }

    fun hasRoute(): Boolean = target != null && (destLat != null || !target?.label.isNullOrBlank())

    fun currentStreetCached(): String? = cachedStreet

    /** Hint rápido sin refrescar GPS (para no retrasar «qué ves»). */
    fun cachedContextHint(): String? {
        if (!hasRoute()) return null
        val name = target?.label?.ifBlank { "destino" } ?: "destino"
        return buildString {
            append("Destino: $name.")
            cachedDistanceM?.takeIf { it > 0 }?.let { append(" Quedan unos $it metros.") }
            cachedStreet?.takeIf { it.isNotBlank() }?.let { append(" Calle actual: $it.") }
            cachedNextManeuver?.takeIf { it.isNotBlank() }?.let { append(" $it") }
            cachedAction?.let { action ->
                val verb = when (action) {
                    BlindNavigationPhraseBuilder.Action.TURN_LEFT -> "Siguiente: girar a la izquierda."
                    BlindNavigationPhraseBuilder.Action.TURN_RIGHT -> "Siguiente: girar a la derecha."
                    BlindNavigationPhraseBuilder.Action.FORWARD -> "Siguiente: seguir recto."
                    BlindNavigationPhraseBuilder.Action.ARRIVE -> "Casi en destino."
                    else -> null
                }
                verb?.let { append(" $it") }
            }
        }
    }

    /** Rumbo aproximado hacia el siguiente tramo o destino (grados 0–360). */
    fun bearingHintDegrees(): Float? {
        val progress = lastProgress
        val poly = routePlan?.polyline
        if (progress != null && poly != null && poly.size >= 2) {
            val idx = progress.nearestIndex.coerceIn(0, poly.size - 2)
            val a = poly[idx]
            val b = poly[(idx + 1).coerceAtMost(poly.lastIndex)]
            return NavigationBearing.bearingDeg(a.lat, a.lng, b.lat, b.lng)
        }
        val dLat = destLat ?: return null
        val dLng = destLng ?: return null
        val origin = highAccuracyLocationProvider.lastFixCached() ?: return null
        return NavigationBearing.bearingDeg(origin.lat, origin.lng, dLat, dLng)
    }

    suspend fun snapshot(): NavigationGuidanceSnapshot {
        refreshCache()
        return NavigationGuidanceSnapshot(
            label = target?.label?.ifBlank { "tu destino" } ?: "tu destino",
            destLat = destLat,
            destLng = destLng,
            distanceMeters = cachedDistanceM,
            etaMinutes = cachedEtaMinutes,
            currentStreet = cachedStreet,
            metersToNextManeuver = cachedMetersToManeuver,
            action = cachedAction,
            nextManeuverHint = cachedNextManeuver,
            lastTip = lastOwnTip,
            tipsActive = active.get(),
            offRoute = cachedOffRoute,
        )
    }

    private suspend fun runGuideLoop(announceInitial: Boolean) {
        if (announceInitial) {
            delay(INITIAL_DELAY_MS)
            if (!active.get()) return
            prepareRoute()
            if (!active.get()) return
            announceNow(forceInitial = true)
        }
        while (active.get()) {
            val nearManeuver = (cachedMetersToManeuver ?: Int.MAX_VALUE) <= 90
            delay(if (nearManeuver) LOOP_NEAR_MS else LOOP_INTERVAL_MS)
            if (!active.get()) break
            if (audioCoordinator.shouldDeferMapsSpeech()) continue
            if (textToSpeechManager.isSpeaking.value) continue
            announceNow(forceInitial = false)
        }
    }

    private suspend fun prepareRoute() {
        val label = target?.label.orEmpty()
        var lat = destLat
        var lng = destLng
        if (lat == null || lng == null) {
            val geo = geocodeNear(label)
            if (geo == null) {
                Log.w(TAG, "No pude geocodificar destino=$label")
                speakTip(
                    "No encuentro la dirección de $label. " +
                        "Camina con cuidado; di para cuando quieras terminar.",
                )
                return
            }
            lat = geo.first
            lng = geo.second
            destLat = lat
            destLng = lng
        }
        val origin = resolveGuideOrigin()
        if (origin == null) {
            Log.w(TAG, "Sin GPS para planificar ruta a $label")
            speakTip(
                "Aún no tengo buena ubicación GPS. Sal un momento al exterior " +
                    "o espera unos segundos. Te guiaré en cuanto pueda.",
            )
            return
        }
        fetchAndApplyPlan(origin.latitude, origin.longitude, lat, lng, label)
        if (routePlan == null) {
            speakTip(
                "No pude calcular la ruta ahora. Te oriento por rumbo hacia $label.",
            )
        }
    }

    private suspend fun fetchAndApplyPlan(
        originLat: Double,
        originLng: Double,
        destLat: Double,
        destLng: Double,
        label: String,
    ) {
        val plan = withContext(Dispatchers.IO) {
            googleDirectionsRouter.fetchRoutePlan(originLat, originLng, destLat, destLng)
                ?.also { routeSource = "google" }
                ?: osrmFootRouter.fetchRoutePlan(originLat, originLng, destLat, destLng)
                    ?.also { routeSource = "osrm" }
        }
        if (plan != null) {
            routePlan = plan
            steps = plan.steps
            stepIndex = 0
            announcedManeuverBands.clear()
            lastOwnTurnWindowStep = -1
            progressTracker.load(plan.polyline, plan.totalDistanceM)
            Log.i(
                TAG,
                "Route plan source=$routeSource steps=${plan.steps.size} " +
                    "dist=${plan.totalDistanceM.toInt()}m " +
                    "eta=${(plan.totalDurationS / 60).toInt()}min poly=${plan.polyline.size} dest=$label",
            )
        } else {
            routePlan = null
            steps = emptyList()
            routeSource = "none"
            progressTracker.clear()
            Log.i(TAG, "Directions y OSRM fallaron; uso rumbo a destino")
        }
    }

    private suspend fun announceNow(forceInitial: Boolean) {
        val dLat = destLat
        val dLng = destLng
        val label = target?.label?.ifBlank { "tu destino" } ?: "tu destino"
        val origin = resolveGuideOrigin()
        if (dLat == null || dLng == null || origin == null) {
            if (forceInitial && !announcedInitial) {
                announcedInitial = true
                speakTip("Empieza a caminar hacia $label. Te iré guiando.")
            }
            return
        }

        val straightDist = NavigationBearing.distanceMeters(
            origin.latitude, origin.longitude, dLat, dLng,
        )
        if (straightDist <= ARRIVE_RADIUS_M) {
            if (!mapsBlocksStrict()) {
                speakTip(
                    BlindNavigationPhraseBuilder.primaryTip(
                        BlindNavigationPhraseBuilder.Action.ARRIVE,
                    ),
                )
            }
            return
        }

        val progress = if (progressTracker.isLoaded()) {
            progressTracker.update(origin.latitude, origin.longitude, OFF_ROUTE_M)
        } else {
            null
        }
        lastProgress = progress

        if (progress != null && progress.offRoute) {
            cachedOffRoute = true
            val now = System.currentTimeMillis()
            if (now - lastRecalcMs >= RECALC_COOLDOWN_MS && !mapsBlocksStrict()) {
                lastRecalcMs = now
                speakTip(BlindNavigationPhraseBuilder.announceOffRouteRecalc())
                fetchAndApplyPlan(origin.latitude, origin.longitude, dLat, dLng, label)
                return
            }
        } else {
            cachedOffRoute = false
        }

        advanceStepFromProgress(progress, origin.latitude, origin.longitude)
        refreshCacheFrom(origin, dLat, dLng, straightDist, progress)
        maybeOpenOwnTurnWindow(progress)
        maybeMarkAfterTurn()

        if (!forceInitial && !mapsBlocksStrict()) {
            // Prioridad: cruce / giro / rumbo / calle / recto / hitos
            maybeAnnounceCrossing(origin, progress)?.let { tip ->
                speakTip(tip, urgent = true)
                return
            }
            maybeAnnounceNextManeuver(progress)?.let { tip ->
                speakTip(tip, urgent = true)
                return
            }
            maybeAnnounceAfterTurn()?.let { tip ->
                speakTip(tip, urgent = true)
                return
            }
            maybeAnnounceHeadingCorrection(origin, dLat, dLng, progress)?.let { tip ->
                speakTip(tip, urgent = true)
                return
            }
            maybeAnnounceStreetChange()?.let { tip ->
                speakTip(tip)
                return
            }
            maybeAnnounceContinueStraight(progress)?.let { tip ->
                speakTip(tip)
                return
            }
            maybeAnnounceLandmark(origin)?.let { tip ->
                speakTip(tip)
                return
            }
            maybeAnnounceMilestone(progress, straightDist.toInt(), label)?.let { tip ->
                speakTip(tip)
                return
            }
            maybeAnnounceStreetSide(cachedStreet)?.let { tip ->
                speakTip(tip)
                return
            }
            // Sin tip especial: no repetir «camina adelante» cada ciclo (silencio → pitidos).
            return
        }

        if (!forceInitial && mapsBlocksHeading()) return

        val heading = currentHeadingDeg()
        if (forceInitial || !announcedInitial) {
            announcedInitial = true
            val plan = routePlan
            if (plan != null && plan.totalDistanceM > 0) {
                val firstNamed = plan.steps.firstOrNull {
                    !it.name.isNullOrBlank() &&
                        osrmFootRouter.actionForStep(it) != BlindNavigationPhraseBuilder.Action.ARRIVE
                }
                val firstAction = plan.steps.firstOrNull()?.let { osrmFootRouter.actionForStep(it) }
                    ?: BlindNavigationPhraseBuilder.Action.FORWARD
                speakTip(
                    BlindNavigationPhraseBuilder.announceRouteStart(
                        label = label,
                        totalDistanceM = plan.totalDistanceM.toInt(),
                        etaMinutes = (plan.totalDurationS / 60.0).toInt().coerceAtLeast(1),
                        firstStreet = firstNamed?.name,
                        firstAction = firstAction,
                    ),
                    urgent = true,
                )
                lastContinueStraightMs = System.currentTimeMillis()
                return
            }
            if (heading == null) {
                speakTip(BlindNavigationPhraseBuilder.announceCalibrateHeading(label), urgent = true)
                return
            }
            val (action, distanceForTip) = resolveActionAndDistance(
                origin, dLat, dLng, straightDist, heading, progress,
            )
            speakTip(
                BlindNavigationPhraseBuilder.announceInitialHeading(action, distanceForTip, label),
                urgent = true,
            )
            lastContinueStraightMs = System.currentTimeMillis()
            return
        }
    }

    private fun advanceStepFromProgress(progress: PolylineProgress?, lat: Double, lng: Double) {
        if (steps.isEmpty()) return
        if (progress != null) {
            val along = progress.alongM
            while (stepIndex < steps.size - 1) {
                val next = steps[stepIndex + 1]
                // Avanzar cuando estamos cerca del siguiente maniobra
                if (along + STEP_ADVANCE_ALONG_M >= next.startAlongM) {
                    stepIndex++
                } else {
                    break
                }
            }
            return
        }
        // Fallback sin polilínea
        while (stepIndex < steps.size) {
            val step = steps[stepIndex]
            val sLat = step.locationLat
            val sLng = step.locationLng
            if (sLat == null || sLng == null) {
                if (step.distanceM < 8.0) stepIndex++ else break
                continue
            }
            val d = NavigationBearing.distanceMeters(lat, lng, sLat, sLng)
            if (d < STEP_ADVANCE_M) stepIndex++ else break
        }
    }

    private fun nextManeuverStep(): OsrmStep? {
        if (steps.isEmpty()) return null
        for (i in stepIndex until steps.size) {
            val step = steps[i]
            val action = osrmFootRouter.actionForStep(step)
            if (action != BlindNavigationPhraseBuilder.Action.FORWARD &&
                action != BlindNavigationPhraseBuilder.Action.OTHER
            ) {
                return step
            }
            // "new name" con calle distinta también cuenta como tip de calle
            if (!step.name.isNullOrBlank() && i > stepIndex) {
                val prevName = steps.getOrNull(i - 1)?.name
                if (!step.name.equals(prevName, ignoreCase = true)) {
                    // No es giro; no devolver como maniobra
                }
            }
        }
        return steps.lastOrNull()
    }

    private fun metersToStep(step: OsrmStep, progress: PolylineProgress?): Int {
        if (progress != null) {
            return (step.startAlongM - progress.alongM).toInt().coerceAtLeast(0)
        }
        return step.distanceM.toInt().coerceAtLeast(0)
    }

    private fun maybeAnnounceNextManeuver(progress: PolylineProgress?): String? {
        val step = nextManeuverStep() ?: return null
        val idx = steps.indexOf(step).takeIf { it >= 0 } ?: stepIndex
        val action = osrmFootRouter.actionForStep(step)
        if (action == BlindNavigationPhraseBuilder.Action.FORWARD ||
            action == BlindNavigationPhraseBuilder.Action.OTHER
        ) {
            return null
        }
        val distM = metersToStep(step, progress)
        val band = when {
            distM <= 12 -> 12
            distM <= 25 -> 25
            distM <= 55 -> 50
            distM <= 120 -> 100
            else -> return null
        }
        val bands = announcedManeuverBands.getOrPut(idx) { mutableSetOf() }
        if (band in bands) return null
        when (band) {
            12 -> bands.addAll(listOf(12, 25, 50, 100))
            25 -> bands.addAll(listOf(25, 50, 100))
            50 -> bands.addAll(listOf(50, 100))
            else -> bands.add(100)
        }
        val tip = BlindNavigationPhraseBuilder.announceNextManeuver(
            action = action,
            distanceM = distM.coerceIn(5, 500),
            streetName = step.name,
        )
        cachedNextManeuver = tip
        lastSpokenManeuverIdx = idx
        afterTurnPending = true
        openTurnFusionForStep(step, idx)
        return tip
    }

    private fun maybeMarkAfterTurn() {
        if (!afterTurnPending || lastSpokenManeuverIdx < 0) return
        // Si ya pasamos el step anunciado, confirmar el giro en el próximo ciclo.
        if (stepIndex > lastSpokenManeuverIdx) {
            // keep afterTurnPending true until spoken
        } else if (stepIndex == lastSpokenManeuverIdx) {
            val step = steps.getOrNull(stepIndex) ?: return
            val dist = lastProgress?.let { metersToStep(step, it) } ?: return
            if (dist <= 8) {
                // casi en el punto del giro: esperar a avanzar
            }
        }
    }

    private fun maybeAnnounceAfterTurn(): String? {
        if (!afterTurnPending || lastSpokenManeuverIdx < 0) return null
        if (stepIndex <= lastSpokenManeuverIdx) return null
        afterTurnPending = false
        lastContinueStraightMs = System.currentTimeMillis()
        return BlindNavigationPhraseBuilder.announceAfterTurn()
    }

    private fun maybeAnnounceContinueStraight(progress: PolylineProgress?): String? {
        val now = System.currentTimeMillis()
        if (now - lastContinueStraightMs < CONTINUE_STRAIGHT_MS) return null
        val metersToMan = cachedMetersToManeuver
        // Cerca de un giro: no decir «sigue recto», ya habrá maniobra.
        if (metersToMan != null && metersToMan <= 55) return null
        val action = cachedAction
        if (action != null &&
            action != BlindNavigationPhraseBuilder.Action.FORWARD &&
            action != BlindNavigationPhraseBuilder.Action.OTHER
        ) {
            return null
        }
        lastContinueStraightMs = now
        continueStraightVariation++
        return BlindNavigationPhraseBuilder.announceContinueStraight(
            metersToNextManeuver = metersToMan,
            streetName = cachedStreet,
            variation = continueStraightVariation,
        )
    }

    private fun maybeAnnounceHeadingCorrection(
        origin: LocationAction.UserLocation,
        dLat: Double,
        dLng: Double,
        progress: PolylineProgress?,
    ): String? {
        val now = System.currentTimeMillis()
        if (now - lastHeadingCorrectMs < HEADING_CORRECT_MS) return null
        val heading = currentHeadingDeg() ?: return null
        val targetBearing = bearingHintDegrees()
            ?: NavigationBearing.bearingDeg(origin.latitude, origin.longitude, dLat, dLng)
        val rel = NavigationBearing.relativeBearingDeg(heading, targetBearing)
        val absRel = kotlin.math.abs(rel)
        if (absRel < 35f) return null
        // Si hay maniobra cercana, no contradecir el giro previsto.
        val metersToMan = cachedMetersToManeuver
        if (metersToMan != null && metersToMan <= 40) return null
        val action = NavigationBearing.actionFromRelativeBearing(rel)
        val tip = BlindNavigationPhraseBuilder.announceHeadingCorrection(action) ?: return null
        lastHeadingCorrectMs = now
        return tip
    }

    private fun maybeAnnounceStreetChange(): String? {
        val street = cachedStreet?.trim()?.takeIf { it.length >= 2 } ?: return null
        if (street.equals(lastAnnouncedStreet, ignoreCase = true)) return null
        // No anunciar la primera calle del arranque (ya va en route start).
        if (lastAnnouncedStreet == null && announcedInitial) {
            lastAnnouncedStreet = street
            return null
        }
        lastAnnouncedStreet = street
        return BlindNavigationPhraseBuilder.announceStreetChange(street)
    }

    private fun maybeOpenOwnTurnWindow(progress: PolylineProgress?) {
        val step = nextManeuverStep() ?: return
        val idx = steps.indexOf(step).takeIf { it >= 0 } ?: return
        val distM = metersToStep(step, progress)
        if (distM in 1..OWN_TURN_WINDOW_M) {
            openTurnFusionForStep(step, idx)
        }
    }

    private fun openTurnFusionForStep(step: OsrmStep, idx: Int) {
        if (idx == lastOwnTurnWindowStep &&
            audioCoordinator.isWithinTurnWindow()
        ) {
            return
        }
        lastOwnTurnWindowStep = idx
        val type = osrmFootRouter.mapsTypeForStep(step)
        val side = osrmFootRouter.turnSideForStep(step)
        if (type == MapsInstructionType.TURN ||
            type == MapsInstructionType.ROUNDABOUT ||
            type == MapsInstructionType.CROSS_STREET ||
            type == MapsInstructionType.ARRIVE
        ) {
            mapsVisionFusionCoordinator.onOwnInstruction(
                type = type,
                side = side,
                rawText = step.name.orEmpty(),
            )
        }
    }

    private fun maybeAnnounceMilestone(
        progress: PolylineProgress?,
        straightMeters: Int,
        label: String,
    ): String? {
        val metersLeft = (progress?.remainingM?.toInt() ?: straightMeters).coerceAtLeast(0)
        val plan = routePlan
        // Hitos cada ~500 m con ETA
        if (plan != null && metersLeft >= 450) {
            val band500 = ((metersLeft + 250) / 500) * 500
            if (band500 >= 500 && band500 !in announcedEtaMilestones) {
                val crossed = DISTANCE_MILESTONES.any { metersLeft <= it }
                if (!crossed) {
                    announcedEtaMilestones.add(band500)
                    val eta = estimateEtaMinutes(metersLeft, plan)
                    return BlindNavigationPhraseBuilder.announceDistanceMilestone(
                        metersLeft = metersLeft,
                        label = label,
                        etaMinutes = eta,
                    )
                }
            }
        }
        val crossed = DISTANCE_MILESTONES.filter { metersLeft <= it }
        if (crossed.isEmpty()) return null
        val current = crossed.minOrNull() ?: return null
        for (t in DISTANCE_MILESTONES) {
            if (t > current) announcedMilestones.add(t)
        }
        if (current in announcedMilestones) return null
        announcedMilestones.add(current)
        val eta = plan?.let { estimateEtaMinutes(metersLeft, it) }
        return BlindNavigationPhraseBuilder.announceDistanceMilestone(current, label, eta)
    }

    private fun estimateEtaMinutes(metersLeft: Int, plan: OsrmRoutePlan): Int {
        if (plan.totalDistanceM <= 1) return 1
        val ratio = metersLeft / plan.totalDistanceM
        return (plan.totalDurationS * ratio / 60.0).toInt().coerceAtLeast(1)
    }

    private suspend fun maybeAnnounceLandmark(origin: LocationAction.UserLocation): String? {
        val now = System.currentTimeMillis()
        if (now - lastLandmarkMs < LANDMARK_COOLDOWN_MS) return null
        val heading = currentHeadingDeg()
        val nearby = savedPlaceRepository.findNearby(
            origin.latitude,
            origin.longitude,
            LANDMARK_RADIUS_M,
        )
        val destLabel = target?.label.orEmpty()
        val best = nearby.firstOrNull { place ->
            !place.displayName.equals(destLabel, ignoreCase = true) &&
                place.key != lastLandmarkKey
        } ?: return null
        val dist = NavigationBearing.distanceMeters(
            origin.latitude, origin.longitude, best.latitude, best.longitude,
        ).toInt().coerceAtLeast(5)
        val rounded = when {
            dist <= 40 -> dist
            dist <= 200 -> (dist / 10) * 10
            else -> (dist / 25) * 25
        }
        val direction = if (heading != null) {
            val bearing = NavigationBearing.bearingDeg(
                origin.latitude, origin.longitude, best.latitude, best.longitude,
            )
            val rel = NavigationBearing.relativeBearingDeg(heading, bearing)
            when (NavigationBearing.actionFromRelativeBearing(rel)) {
                BlindNavigationPhraseBuilder.Action.TURN_LEFT -> "a tu izquierda"
                BlindNavigationPhraseBuilder.Action.TURN_RIGHT -> "a tu derecha"
                BlindNavigationPhraseBuilder.Action.U_TURN -> "detrás de ti"
                else -> "hacia adelante"
            }
        } else {
            "cerca"
        }
        lastLandmarkKey = best.key
        lastLandmarkMs = now
        return "Referencia: estás a unos $rounded metros de ${best.displayName}, $direction."
    }

    private suspend fun maybeAnnounceCrossing(
        origin: LocationAction.UserLocation,
        progress: PolylineProgress?,
    ): String? {
        val now = System.currentTimeMillis()
        if (now - lastCrossingTipMs < CROSSING_COOLDOWN_MS) return null
        val next = nextManeuverStep()
        val nextAction = next?.let { osrmFootRouter.actionForStep(it) }
        val distToManeuver = next?.let { metersToStep(it, progress) } ?: Int.MAX_VALUE
        // Priorizar si la ruta dice cruzar, o si hay crossing OSM cerca.
        val routeSaysCross = nextAction == BlindNavigationPhraseBuilder.Action.CROSS &&
            distToManeuver <= 100
        if (!routeSaysCross && distToManeuver > 120 && progress == null) return null

        if (routeSaysCross && distToManeuver <= 30) {
            lastCrossingTipMs = now
            mapsVisionFusionCoordinator.onOwnInstruction(
                type = MapsInstructionType.CROSS_STREET,
                rawText = "cruce peatonal",
            )
            audioCoordinator.openOwnCrossSearch()
            return BlindNavigationPhraseBuilder.announceNextManeuver(
                BlindNavigationPhraseBuilder.Action.CROSS,
                distToManeuver.coerceIn(5, 100),
                next?.name,
            )
        }

        val hint = ojenMapBundle.nearestCrossing(
            origin.latitude,
            origin.longitude,
            CROSSING_HINT_RADIUS_M,
        ) ?: return null
        lastCrossingTipMs = now
        if (hint.distanceM <= 50) {
            mapsVisionFusionCoordinator.onOwnInstruction(
                type = MapsInstructionType.CROSS_STREET,
                rawText = "cruce peatonal",
            )
            audioCoordinator.openOwnCrossSearch()
        }
        val side = crossingSideRelative(origin, hint.lat, hint.lng)
        return BlindNavigationPhraseBuilder.announceCrossingAhead(hint.distanceM.toInt(), side)
    }

    private fun crossingSideRelative(
        origin: LocationAction.UserLocation,
        crossLat: Double,
        crossLng: Double,
    ): BlindNavigationPhraseBuilder.CrossingSide {
        val heading = currentHeadingDeg() ?: return BlindNavigationPhraseBuilder.CrossingSide.AHEAD
        val bearing = NavigationBearing.bearingDeg(
            origin.latitude, origin.longitude, crossLat, crossLng,
        )
        val rel = NavigationBearing.relativeBearingDeg(heading, bearing)
        return when {
            rel < -25f -> BlindNavigationPhraseBuilder.CrossingSide.LEFT
            rel > 25f -> BlindNavigationPhraseBuilder.CrossingSide.RIGHT
            else -> BlindNavigationPhraseBuilder.CrossingSide.AHEAD
        }
    }

    private fun resolveActionAndDistance(
        origin: LocationAction.UserLocation,
        dLat: Double,
        dLng: Double,
        dist: Double,
        heading: Float?,
        progress: PolylineProgress?,
    ): Pair<BlindNavigationPhraseBuilder.Action, Int> {
        val maneuver = nextManeuverStep()
        if (maneuver != null) {
            val stepAction = osrmFootRouter.actionForStep(maneuver)
            val meters = metersToStep(maneuver, progress).coerceIn(5, 400)
            val action = if (heading != null && maneuver.bearingAfter != null &&
                stepAction == BlindNavigationPhraseBuilder.Action.FORWARD
            ) {
                val rel = NavigationBearing.relativeBearingDeg(heading, maneuver.bearingAfter)
                NavigationBearing.actionFromRelativeBearing(rel)
            } else {
                stepAction
            }
            return action to meters
        }
        if (steps.isNotEmpty() && stepIndex < steps.size) {
            val step = steps[stepIndex]
            val stepAction = osrmFootRouter.actionForStep(step)
            return stepAction to metersToStep(step, progress).coerceIn(5, 400)
        }
        if (heading != null) {
            val targetBearing = NavigationBearing.bearingDeg(
                origin.latitude, origin.longitude, dLat, dLng,
            )
            val rel = NavigationBearing.relativeBearingDeg(heading, targetBearing)
            return NavigationBearing.actionFromRelativeBearing(rel) to
                dist.toInt().coerceIn(5, 2_000)
        }
        return BlindNavigationPhraseBuilder.Action.FORWARD to dist.toInt().coerceIn(5, 2_000)
    }

    private suspend fun refreshCache() {
        val dLat = destLat ?: return
        val dLng = destLng ?: return
        val origin = locationAction.getCurrentLocation()
            ?: highAccuracyLocationProvider.lastFix()?.let {
                LocationAction.UserLocation(it.lat, it.lng)
            }
            ?: return
        val dist = NavigationBearing.distanceMeters(
            origin.latitude, origin.longitude, dLat, dLng,
        )
        val progress = if (progressTracker.isLoaded()) {
            progressTracker.update(origin.latitude, origin.longitude, OFF_ROUTE_M)
        } else {
            null
        }
        advanceStepFromProgress(progress, origin.latitude, origin.longitude)
        refreshCacheFrom(origin, dLat, dLng, dist, progress)
    }

    private fun refreshCacheFrom(
        origin: LocationAction.UserLocation,
        dLat: Double,
        dLng: Double,
        dist: Double,
        progress: PolylineProgress?,
    ) {
        val heading = currentHeadingDeg()
        val (action, _) = resolveActionAndDistance(origin, dLat, dLng, dist, heading, progress)
        cachedAction = action
        cachedDistanceM = (progress?.remainingM?.toInt() ?: dist.toInt()).coerceAtLeast(0)
        cachedOffRoute = progress?.offRoute == true
        val plan = routePlan
        cachedEtaMinutes = if (plan != null && cachedDistanceM != null) {
            estimateEtaMinutes(cachedDistanceM!!, plan)
        } else {
            null
        }
        cachedStreet = steps.getOrNull(stepIndex)?.name?.takeIf { it.length >= 2 }
        val maneuver = nextManeuverStep()
        if (maneuver != null) {
            val stepAction = osrmFootRouter.actionForStep(maneuver)
            val meters = metersToStep(maneuver, progress)
            cachedMetersToManeuver = meters
            if (stepAction != BlindNavigationPhraseBuilder.Action.FORWARD &&
                stepAction != BlindNavigationPhraseBuilder.Action.OTHER
            ) {
                cachedNextManeuver = BlindNavigationPhraseBuilder.announceNextManeuver(
                    stepAction,
                    meters.coerceIn(5, 400),
                    maneuver.name,
                )
            }
        } else {
            cachedMetersToManeuver = null
        }
    }

    private fun mapsBlocksStrict(): Boolean = false

    private fun mapsBlocksHeading(): Boolean = false

    private suspend fun resolveGuideOrigin(): LocationAction.UserLocation? {
        // Preferir stream HIGH_ACCURACY; aceptar hasta 40 m al inicio para no quedarnos mudos.
        val hi = highAccuracyLocationProvider.lastFixCached()
            ?: highAccuracyLocationProvider.lastFix()
        if (hi != null && hi.accuracyM <= MAX_GPS_ACCURACY_START_M) {
            return LocationAction.UserLocation(hi.lat, hi.lng)
        }
        val cached = highAccuracyLocationProvider.lastGoodFix()
        if (cached != null) {
            return LocationAction.UserLocation(cached.lat, cached.lng)
        }
        return locationAction.getCurrentLocation()
    }

    private suspend fun maybeAnnounceStreetSide(street: String?): String? {
        if (street.isNullOrBlank()) return null
        val now = System.currentTimeMillis()
        val key = StreetSidePreferenceRepository.normalizeStreet(street)
        if (key == lastStreetSideKey && now - lastStreetSideTipMs < STREET_SIDE_COOLDOWN_MS) {
            return null
        }
        val tip = streetSidePreferenceRepository.hintLineForStreet(street) ?: return null
        lastStreetSideKey = key
        lastStreetSideTipMs = now
        return tip
    }

    private fun currentHeadingDeg(): Float? {
        val compass = deviceRotationTracker.compassHeadingDeg()
        if (compass != null) return compass
        val gps = highAccuracyLocationProvider.lastFixCached()?.bearingDeg
        return if (gps != null && gps > 0f) NavigationBearing.normalizeHeadingDeg(gps) else null
    }

    private suspend fun speakTip(tip: String, urgent: Boolean = false) {
        if (sleepModeController.isSleeping()) return
        val now = System.currentTimeMillis()
        if (tip == lastOwnTip && now - lastOwnTipMs < MIN_SAME_TIP_MS) return
        if (!urgent && now - lastOwnTipMs < MIN_TIP_INTERVAL_MS) return
        lastOwnTip = tip
        lastOwnTipMs = now
        try {
            textToSpeechManager.speak(tip)
        } catch (_: Exception) {
        }
    }

    private suspend fun geocodeNear(query: String): Pair<Double, Double>? {
        if (query.isBlank() || !Geocoder.isPresent()) return null
        val origin = locationAction.getCurrentLocation()
        return withContext(Dispatchers.IO) {
            try {
                @Suppress("DEPRECATION")
                val geocoder = Geocoder(context, Locale("es", "ES"))
                val results = if (origin != null) {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocationName(
                        query,
                        3,
                        origin.latitude - 0.08,
                        origin.longitude - 0.08,
                        origin.latitude + 0.08,
                        origin.longitude + 0.08,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocationName(query, 3)
                }
                val best = results?.firstOrNull() ?: return@withContext null
                best.latitude to best.longitude
            } catch (_: Exception) {
                null
            }
        }
    }

    companion object {
        private const val TAG = "OwnNavigationGuide"
        private const val INITIAL_DELAY_MS = 1_000L
        private const val LOOP_INTERVAL_MS = 4_000L
        private const val LOOP_NEAR_MS = 2_500L
        private const val MIN_SAME_TIP_MS = 10_000L
        private const val MIN_TIP_INTERVAL_MS = 7_000L
        private const val CONTINUE_STRAIGHT_MS = 18_000L
        private const val HEADING_CORRECT_MS = 14_000L
        private const val ARRIVE_RADIUS_M = 18.0
        private const val STEP_ADVANCE_M = 22.0
        private const val STEP_ADVANCE_ALONG_M = 12.0
        private const val OFF_ROUTE_M = 35.0
        private const val RECALC_COOLDOWN_MS = 45_000L
        private const val OWN_TURN_WINDOW_M = 40
        private const val LANDMARK_RADIUS_M = 180.0
        private const val LANDMARK_COOLDOWN_MS = 45_000L
        private const val CROSSING_HINT_RADIUS_M = 90.0
        private const val CROSSING_COOLDOWN_MS = 28_000L
        private const val MAX_GPS_ACCURACY_M = 25f
        private const val MAX_GPS_ACCURACY_START_M = 40f
        private const val STREET_SIDE_COOLDOWN_MS = 90_000L
        private val DISTANCE_MILESTONES = listOf(200, 100, 50, 25)
    }
}
