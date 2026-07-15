package io.lazaro.routes.replay

import io.lazaro.pathguide.BeepSignalStabilizer
import io.lazaro.pathguide.CorridorState
import io.lazaro.pathguide.DoorwayVoiceCue
import io.lazaro.pathguide.ExitBrainFrameResult
import io.lazaro.pathguide.ExitBrainPhase
import io.lazaro.pathguide.JunctionType
import io.lazaro.pathguide.RoadSide
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
    private var lastMatch: RouteMatchState? = null

    fun reset() {
        beepStabilizer.reset()
        lastDriftCueMs = 0L
        lastStraightCueMs = 0L
        lastTurnCueMs = 0L
        lastSafeSideCueMs = 0L
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
        val (leftBeep, rightBeep, warning) = buildBeeps(expected, corridor)

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

        if (match.lateralOffsetM >= RouteMapMatcher.DRIFT_WARN_M &&
            now - lastDriftCueMs >= DRIFT_DEBOUNCE_MS
        ) {
            lastDriftCueMs = now
            val side = when (expected.safeSide) {
                "RIGHT" -> "derecha"
                "LEFT" -> "izquierda"
                else -> "el centro del camino"
            }
            return DoorwayVoiceCue(
                message = "Te desvías. Vuelve a $side, pega el bastón al borde.",
                debounceMs = DRIFT_DEBOUNCE_MS,
                cueId = "route_drift",
            )
        }

        val yawDelta = abs(yawDeg - expected.yawDeg)
        if (yawDelta > 25f && now - lastTurnCueMs >= TURN_DEBOUNCE_MS) {
            lastTurnCueMs = now
            val dir = if (yawDeg > expected.yawDeg) "izquierda" else "derecha"
            return DoorwayVoiceCue(
                message = "Gira un poco a la $dir.",
                debounceMs = TURN_DEBOUNCE_MS,
                cueId = "route_turn",
            )
        }

        if (expected.segmentType == "wooded" || expected.segmentType == "rural_lane") {
            if (now - lastStraightCueMs >= STRAIGHT_DEBOUNCE_MS) {
                lastStraightCueMs = now
                val meters = ((match.distanceAlongM % 40f).toInt().coerceAtLeast(10))
                return DoorwayVoiceCue(
                    message = "Sigue recto unos $meters metros.",
                    debounceMs = STRAIGHT_DEBOUNCE_MS,
                    cueId = "route_straight",
                )
            }
        }

        if (now - lastSafeSideCueMs >= SAFE_SIDE_DEBOUNCE_MS) {
            lastSafeSideCueMs = now
            val hint = when (expected.safeSide) {
                "RIGHT" -> "Camina pegado a la derecha."
                "LEFT" -> "Camina pegado a la izquierda."
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
        expected: io.lazaro.routes.model.CanonicalProfilePoint?,
        corridor: CorridorState,
    ): Triple<Float, Float, Boolean> {
        if (expected == null) return Triple(0f, 0f, false)

        var left = 0f
        var right = 0f
        when (expected.safeSide) {
            "LEFT" -> left = max(corridor.leftProximity, 0.38f)
            "RIGHT" -> right = max(corridor.rightProximity, 0.38f)
            else -> {
                left = corridor.leftProximity
                right = corridor.rightProximity
            }
        }

        val (stableLeft, stableRight) = beepStabilizer.stabilize(left, right, doorwayMode = false)
        return Triple(stableLeft, stableRight, false)
    }

    companion object {
        private const val DRIFT_DEBOUNCE_MS = 6_000L
        private const val TURN_DEBOUNCE_MS = 5_000L
        private const val STRAIGHT_DEBOUNCE_MS = 12_000L
        private const val SAFE_SIDE_DEBOUNCE_MS = 25_000L
    }
}
