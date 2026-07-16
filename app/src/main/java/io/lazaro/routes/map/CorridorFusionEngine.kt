package io.lazaro.routes.map

/**
 * Fusión de confianza GPS + ODM + cámara + heatmap para replay de ruta.
 */
object CorridorFusionEngine {

    data class FusionInput(
        val corridorScore: Float,
        val gpsScore: Float,
        val heatConfidence: Float,
        val odmScore: Float,
        val hasHeatmap: Boolean,
        val onCorridor: Boolean,
    )

    fun computeConfidence(input: FusionInput): Float {
        val odm = input.odmScore.coerceIn(0f, 1f)
        val corridor = input.corridorScore.coerceIn(0f, 1f)
        val gps = input.gpsScore.coerceIn(0f, 1f)
        val heat = input.heatConfidence.coerceIn(0f, 1f)

        val confidence = if (input.hasHeatmap) {
            heat * 0.35f + odm * 0.25f + corridor * 0.25f + gps * 0.15f
        } else if (odm > 0.05f) {
            odm * 0.50f + corridor * 0.30f + gps * 0.20f
        } else {
            corridor * 0.45f + gps * 0.35f + heat * 0.20f
        }

        val boosted = if (input.onCorridor && odm >= 0.75f) {
            confidence + 0.05f
        } else {
            confidence
        }
        return boosted.coerceIn(0f, 1f)
    }

    fun replayThreshold(odmScore: Float, onCorridor: Boolean): Float {
        return if (onCorridor && odmScore >= 0.75f) {
            0.50f
        } else {
            0.55f
        }
    }
}
