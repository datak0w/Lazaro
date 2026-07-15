package io.lazaro.pathguide

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class BypassAdvisor @Inject constructor() {

    fun advise(corridor: CorridorState): BypassAdvice {
        val left = corridor.leftProximity
        val right = corridor.rightProximity
        val delta = abs(left - right)

        if (left > 0.70f && right > 0.70f) {
            return BypassAdvice(BypassSide.STOP, left, right)
        }

        val leftRoad = looksLikeRoad(left)
        val rightRoad = looksLikeRoad(right)

        val leftScore = left + if (leftRoad) 0.35f else 0f
        val rightScore = right + if (rightRoad) 0.35f else 0f

        if (leftRoad && rightRoad) {
            return BypassAdvice(BypassSide.STOP, left, right)
        }

        if (delta < 0.15f) {
            return BypassAdvice(BypassSide.STOP, left, right)
        }

        return if (leftScore < rightScore) {
            if (left > 0.60f) BypassAdvice(BypassSide.CAUTIOUS_LEFT, left, right)
            else BypassAdvice(BypassSide.LEFT, left, right)
        } else {
            if (right > 0.60f) BypassAdvice(BypassSide.CAUTIOUS_RIGHT, left, right)
            else BypassAdvice(BypassSide.RIGHT, left, right)
        }
    }

    private fun looksLikeRoad(proximity: Float): Boolean {
        return proximity in 0.20f..0.55f
    }
}
