package io.lazaro.navigation

import android.content.Context
import android.location.Geocoder
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.lazaro.actions.LocationAction
import io.lazaro.pathguide.DeviceRotationTracker
import io.lazaro.routes.location.HighAccuracyLocationProvider
import io.lazaro.voice.TextToSpeechManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Guía hablada propia mientras Maps no anuncia:
 * geocode → OSRM foot (o rumbo al destino) → tips con BlindNavigationPhraseBuilder.
 */
@Singleton
class OwnNavigationGuide @Inject constructor(
    @ApplicationContext private val context: Context,
    private val locationAction: LocationAction,
    private val highAccuracyLocationProvider: HighAccuracyLocationProvider,
    private val deviceRotationTracker: DeviceRotationTracker,
    private val osrmFootRouter: OsrmFootRouter,
    private val textToSpeechManager: TextToSpeechManager,
    private val navigationGuidanceMonitor: NavigationGuidanceMonitor,
    private val audioCoordinator: NavigationAudioCoordinator,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val active = AtomicBoolean(false)
    private var loopJob: Job? = null
    private var target: NavigationTarget? = null
    private var destLat: Double? = null
    private var destLng: Double? = null
    private var steps: List<OsrmStep> = emptyList()
    private var stepIndex = 0
    private var lastOwnTip: String? = null
    private var lastOwnTipMs = 0L
    private var announcedInitial = false

    fun start(navigationTarget: NavigationTarget) {
        stop()
        target = navigationTarget
        destLat = navigationTarget.latitude
        destLng = navigationTarget.longitude
        steps = emptyList()
        stepIndex = 0
        announcedInitial = false
        lastOwnTip = null
        lastOwnTipMs = 0L
        active.set(true)
        deviceRotationTracker.start()
        loopJob = scope.launch {
            delay(INITIAL_DELAY_MS)
            if (!active.get()) return@launch
            prepareRoute()
            if (!active.get()) return@launch
            announceNow(forceInitial = true)
            while (isActive && active.get()) {
                delay(LOOP_INTERVAL_MS)
                if (!active.get()) break
                if (navigationGuidanceMonitor.hasHeardMapsRecently(MAPS_SILENCE_MS)) continue
                if (audioCoordinator.shouldDeferMapsSpeech()) continue
                if (textToSpeechManager.isSpeaking.value) continue
                announceNow(forceInitial = false)
            }
        }
    }

    fun stop() {
        active.set(false)
        loopJob?.cancel()
        loopJob = null
        target = null
        destLat = null
        destLng = null
        steps = emptyList()
        stepIndex = 0
        announcedInitial = false
    }

    private suspend fun prepareRoute() {
        val label = target?.label.orEmpty()
        var lat = destLat
        var lng = destLng
        if (lat == null || lng == null) {
            val geo = geocodeNear(label) ?: return
            lat = geo.first
            lng = geo.second
            destLat = lat
            destLng = lng
        }
        val origin = locationAction.getCurrentLocation()
            ?: highAccuracyLocationProvider.lastFix()?.let {
                LocationAction.UserLocation(it.lat, it.lng)
            }
            ?: return
        val osrm = withContext(Dispatchers.IO) {
            osrmFootRouter.fetchSteps(origin.latitude, origin.longitude, lat, lng)
        }
        if (osrm != null) {
            steps = osrm
            stepIndex = 0
            Log.i(TAG, "OSRM steps=${osrm.size} dest=$label")
        } else {
            Log.i(TAG, "OSRM falló; uso rumbo a destino")
        }
    }

    private suspend fun announceNow(forceInitial: Boolean) {
        val dLat = destLat
        val dLng = destLng
        val label = target?.label?.ifBlank { "tu destino" } ?: "tu destino"
        val origin = locationAction.getCurrentLocation()
            ?: highAccuracyLocationProvider.lastFix()?.let {
                LocationAction.UserLocation(it.lat, it.lng)
            }
        if (dLat == null || dLng == null || origin == null) {
            if (forceInitial && !announcedInitial) {
                announcedInitial = true
                speakTip("Empieza a caminar hacia $label. Te iré guiando.")
            }
            return
        }

        val dist = NavigationBearing.distanceMeters(
            origin.latitude, origin.longitude, dLat, dLng,
        )
        if (dist <= ARRIVE_RADIUS_M) {
            val tip = BlindNavigationPhraseBuilder.primaryTip(
                BlindNavigationPhraseBuilder.Action.ARRIVE,
            )
            speakTip(tip)
            return
        }

        advanceStepIfNeeded(origin.latitude, origin.longitude)

        val heading = currentHeadingDeg()
        val action: BlindNavigationPhraseBuilder.Action
        val distanceForTip: Int
        if (steps.isNotEmpty() && stepIndex < steps.size) {
            val step = steps[stepIndex]
            val stepAction = osrmFootRouter.actionForStep(step)
            if (heading != null && step.bearingAfter != null) {
                val rel = NavigationBearing.relativeBearingDeg(heading, step.bearingAfter)
                action = when (stepAction) {
                    BlindNavigationPhraseBuilder.Action.ARRIVE -> stepAction
                    BlindNavigationPhraseBuilder.Action.FORWARD ->
                        NavigationBearing.actionFromRelativeBearing(rel)
                    else -> stepAction
                }
            } else {
                action = stepAction
            }
            distanceForTip = step.distanceM.toInt().coerceIn(5, 400)
        } else if (heading != null) {
            val targetBearing = NavigationBearing.bearingDeg(
                origin.latitude, origin.longitude, dLat, dLng,
            )
            val rel = NavigationBearing.relativeBearingDeg(heading, targetBearing)
            action = NavigationBearing.actionFromRelativeBearing(rel)
            distanceForTip = dist.toInt().coerceIn(5, 2_000)
        } else {
            action = BlindNavigationPhraseBuilder.Action.FORWARD
            distanceForTip = dist.toInt().coerceIn(5, 2_000)
        }

        val tip = if (forceInitial || !announcedInitial) {
            announcedInitial = true
            BlindNavigationPhraseBuilder.announceInitialHeading(action, distanceForTip, label)
        } else {
            BlindNavigationPhraseBuilder.announceOwnGuidance(action, distanceForTip)
        }
        speakTip(tip)
    }

    private fun advanceStepIfNeeded(lat: Double, lng: Double) {
        while (stepIndex < steps.size) {
            val step = steps[stepIndex]
            val sLat = step.locationLat
            val sLng = step.locationLng
            if (sLat == null || sLng == null) {
                if (step.distanceM < 8.0) stepIndex++ else break
                continue
            }
            val d = NavigationBearing.distanceMeters(lat, lng, sLat, sLng)
            if (d < STEP_ADVANCE_M) {
                stepIndex++
            } else {
                break
            }
        }
    }

    private fun currentHeadingDeg(): Float? {
        val compass = deviceRotationTracker.compassHeadingDeg()
        if (compass != null) return compass
        val gps = highAccuracyLocationProvider.lastFixCached()?.bearingDeg
        return if (gps != null && gps > 0f) NavigationBearing.normalizeHeadingDeg(gps) else null
    }

    private suspend fun speakTip(tip: String) {
        val now = System.currentTimeMillis()
        if (tip == lastOwnTip && now - lastOwnTipMs < MIN_SAME_TIP_MS) return
        if (now - lastOwnTipMs < MIN_TIP_INTERVAL_MS && tip == lastOwnTip) return
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
        private const val INITIAL_DELAY_MS = 1_800L
        private const val LOOP_INTERVAL_MS = 9_000L
        private const val MAPS_SILENCE_MS = 15_000L
        private const val MIN_SAME_TIP_MS = 12_000L
        private const val MIN_TIP_INTERVAL_MS = 7_000L
        private const val ARRIVE_RADIUS_M = 18.0
        private const val STEP_ADVANCE_M = 22.0
    }
}
