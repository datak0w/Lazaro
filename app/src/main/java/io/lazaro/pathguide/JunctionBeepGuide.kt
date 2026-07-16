package io.lazaro.pathguide

import io.lazaro.navigation.TurnSide

/**
 * Pitidos espaciales para elegir rama en bifurcaciones (T) en exterior.
 */
object JunctionBeepGuide {

    data class Signal(
        val leftBeep: Float = 0f,
        val rightBeep: Float = 0f,
        val continuous: Boolean = false,
    )

    fun compute(
        junction: JunctionType,
        corridor: CorridorState,
        mapsTurnSide: TurnSide?,
    ): Signal? {
        if (junction == JunctionType.NONE || junction == JunctionType.DEAD_END) return null

        val leftOpenness = 1f - corridor.leftProximity
        val rightOpenness = 1f - corridor.rightProximity

        return when (junction) {
            JunctionType.T_LEFT -> Signal(
                leftBeep = (0.55f + leftOpenness * 0.35f).coerceIn(0.55f, 0.92f),
                continuous = true,
            )
            JunctionType.T_RIGHT -> Signal(
                rightBeep = (0.55f + rightOpenness * 0.35f).coerceIn(0.55f, 0.92f),
                continuous = true,
            )
            JunctionType.T_BOTH -> {
                val preferLeft = when (mapsTurnSide) {
                    TurnSide.LEFT -> true
                    TurnSide.RIGHT -> false
                    else -> leftOpenness >= rightOpenness
                }
                if (preferLeft) {
                    Signal(leftBeep = 0.72f, continuous = true)
                } else {
                    Signal(rightBeep = 0.72f, continuous = true)
                }
            }
            JunctionType.NONE, JunctionType.DEAD_END -> null
        }
    }
}
