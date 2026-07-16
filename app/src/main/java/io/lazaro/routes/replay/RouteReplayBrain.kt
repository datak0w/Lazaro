package io.lazaro.routes.replay

import io.lazaro.navigation.BlindNavigationPhraseBuilder
import io.lazaro.pathguide.BeepSignalStabilizer
import io.lazaro.pathguide.CorridorState
import io.lazaro.pathguide.DoorwayVoiceCue
import io.lazaro.pathguide.ExitBrainFrameResult
import io.lazaro.pathguide.ExitBrainPhase
import io.lazaro.pathguide.JunctionType
import io.lazaro.routes.model.RouteMatchState
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max

@Singleton
class RouteReplayBrain @Inject constructor(
    private val routeMapMatcher: RouteMapMatcher,
) {
    private val beepStabilizer = BeepSignalStabilizer()
    private var lastDriftCueMs = 0L
    private var lastStraightCueMs = 0L
    private var lastTurnCueMs = 0L
    private var lastSafeSideCueMs = 0L
    private var lastObstacleCueMs = 0L
    private var lastMatch: RouteMatchState? = null

    fun reset() {
        beepStabilizer.reset()
        lastDriftCueMs = 0L
        lastStraightCueMs = 0L
        lastTurnCueMs = 0L
        lastSafeSideCueMs = 0L
        lastObstacleCueMs = 0L
        lastMatch = null
        routeMapMatcher.reset()
    }

    suspend fun loadRoute(routeId: Long) {
        routeMapMatcher.loadRoute(routeId)
    }

    fun update(
        corridor: CorridorState,
        lat: Double,
        lng: Double,
        yawDeg: Float,
        accuracyM: Float,
        now: Long = System.currentTimeMillis(),
    ): ExitBrainFrameResult {
        val match = routeMapMatcher.update(
            lat = lat,
            lng = lng,
            leftP = corridor.leftProximity,
            centerP = corridor.centerProximity,
            rightP = corridor.rightProximity,
            accuracyM = accuracyM,
        )
        lastMatch = match

        if (!match.inReplaySegment) {
            return ExitBrainFrameResult(
                phase = ExitBrainPhase.EXPLORE,
                suppressScene = false,
            )
        }

        val expected = match.expectedPoint
        val voiceCue = buildVoiceCue(match, yawDeg, now)
        val (leftBeep, rightBeep, warning) = buildBeeps(match, corridor)

        return ExitBrainFrameResult(
            phase = ExitBrainPhase.ALIGN,
            leftBeep = leftBeep,
            rightBeep = rightBeep,
            continuousTone = warning,
            warningMode = warning,
            voiceCue = voiceCue,
            isExitGuiding = true,
            suppressScene = false,
            junctionType = JunctionType.NONE,
        )
    }

    fun currentMatch(): RouteMatchState? = lastMatch

    private fun buildVoiceCue(
        match: RouteMatchState,
        yawDeg: Float,
        now: Long,
    ): DoorwayVoiceCue? {
        val expected = match.expectedPoint ?: return null

        // Obstáculo habitual del heatmap (aprendido en paseos previos)
        val ahead = routeMapMatcher.peekAheadObstacle(match.distanceAlongM)
        if (ahead != null && now - lastObstacleCueMs >= OBSTACLE_DEBOUNCE_MS) {
            lastObstacleCueMs = now
            val meters = (ahead.distanceAlongM - match.distanceAlongM).toInt().coerceAtLeast(5)
            return DoorwayVoiceCue(
                message = "Atención: en unos $meters metros suele haber un obstáculo. Ve con cuidado.",
                debounceMs = OBSTACLE_DEBOUNCE_MS,
                cueId = "route_obstacle",
            )
        }

        if (match.heatmapVariance > 0.12f &&
            match.lateralOffsetM >= RouteMapMatcher.DRIFT_WARN_M * 0.7f &&
            now - lastDriftCueMs >= DRIFT_DEBOUNCE_MS
        ) {
            lastDriftCueMs = now
            val side = sideWord(match.heatmapSafeSide.ifBlank { expected.safeSide })
            return DoorwayVoiceCue(
                message = "Zona inestable. Vuelve a $side.",
                debounceMs = DRIFT_DEBOUNCE_MS,
                cueId = "route_drift",
            )
        }

        if (match.lateralOffsetM >= RouteMapMatcher.DRIFT_WARN_M &&
            now - lastDriftCueMs >= DRIFT_DEBOUNCE_MS
        ) {
            lastDriftCueMs = now
            val side = sideWord(match.heatmapSafeSide.ifBlank { expected.safeSide })
            return DoorwayVoiceCue(
                message = "Te desvías. Vuelve a $side, pega el bastón al borde.",
                debounceMs = DRIFT_DEBOUNCE_MS,
                cueId = "route_drift",
            )
        }

        val yawDelta = abs(yawDeg - expected.yawDeg)
        if (yawDelta > 25f && now - lastTurnCueMs >= TURN_DEBOUNCE_MS) {
            lastTurnCueMs = now
            val tip = if (yawDeg > expected.yawDeg) {
                BlindNavigationPhraseBuilder.primaryTip(BlindNavigationPhraseBuilder.Action.TURN_LEFT)
            } else {
                BlindNavigationPhraseBuilder.primaryTip(BlindNavigationPhraseBuilder.Action.TURN_RIGHT)
            }
            return DoorwayVoiceCue(
                message = tip,
                debounceMs = TURN_DEBOUNCE_MS,
                cueId = "route_turn",
            )
        }

        if (expected.segmentType == "wooded" || expected.segmentType == "rural_lane") {
            if (now - lastStraightCueMs >= STRAIGHT_DEBOUNCE_MS) {
                lastStraightCueMs = now
                return DoorwayVoiceCue(
                    message = BlindNavigationPhraseBuilder.primaryTip(
                        BlindNavigationPhraseBuilder.Action.FORWARD,
                    ),
                    debounceMs = STRAIGHT_DEBOUNCE_MS,
                    cueId = "route_straight",
                )
            }
        }

        if (now - lastSafeSideCueMs >= SAFE_SIDE_DEBOUNCE_MS) {
            lastSafeSideCueMs = now
            val safe = match.heatmapSafeSide.ifBlank { expected.safeSide }
            val hint = when (safe) {
                "RIGHT" -> "Camina hacia adelante. Pegado a la derecha."
                "LEFT" -> "Camina hacia adelante. Pegado a la izquierda."
                else -> null
            }
            if (hint != null) {
                return DoorwayVoiceCue(
                    message = hint,
                    debounceMs = SAFE_SIDE_DEBOUNCE_MS,
                    cueId = "route_safe_side",
                )
            }
        }
        return null
    }

    private fun buildBeeps(
        match: RouteMatchState,
        corridor: CorridorState,
    ): Triple<Float, Float, Boolean> {
        val expected = match.expectedPoint ?: return Triple(0f, 0f, false)
        val safe = match.heatmapSafeSide.ifBlank { expected.safeSide }

        // Preferir medias del heatmap (más estables con varios recorridos)
        val targetLeft = if (match.heatmapMeanLeft > 0.05f) match.heatmapMeanLeft else expected.leftP
        val targetRight = if (match.heatmapMeanRight > 0.05f) match.heatmapMeanRight else expected.rightP

        var left = 0f
        var right = 0f
        when (safe) {
            "LEFT" -> {
                val deficit = (targetLeft - corridor.leftProximity).coerceAtLeast(0f)
                left = max(0.38f, 0.45f + deficit)
            }
            "RIGHT" -> {
                val deficit = (targetRight - corridor.rightProximity).coerceAtLeast(0f)
                right = max(0.38f, 0.45f + deficit)
            }
            else -> {
                left = corridor.leftProximity
                right = corridor.rightProximity
            }
        }

        val warning = match.heatmapObstacleHits >= 2 || match.heatmapVariance > 0.15f
        val (stableLeft, stableRight) = beepStabilizer.stabilize(left, right, doorwayMode = warning)
        return Triple(stableLeft, stableRight, warning)
    }

    private fun sideWord(safeSide: String): String = when (safeSide) {
        "RIGHT" -> "la derecha"
        "LEFT" -> "la izquierda"
        else -> "el centro del camino"
    }

    companion object {
        private const val DRIFT_DEBOUNCE_MS = 6_000L
        private const val TURN_DEBOUNCE_MS = 5_000L
        private const val STRAIGHT_DEBOUNCE_MS = 12_000L
        private const val SAFE_SIDE_DEBOUNCE_MS = 25_000L
        private const val OBSTACLE_DEBOUNCE_MS = 18_000L
    }
}
