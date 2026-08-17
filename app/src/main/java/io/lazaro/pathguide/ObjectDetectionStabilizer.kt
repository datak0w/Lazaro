package io.lazaro.pathguide

/**
 * Suaviza detecciones MediaPipe frame a frame: exige persistencia,
 * histéresis de lado y evita saltos de etiqueta.
 */
class ObjectDetectionStabilizer {

    private var stickyCategory: String? = null
    private var stickySpanish: String? = null
    private var stickySide: ObjectSide = ObjectSide.CENTER
    private var stickyScore: Float = 0f
    private var stickyArea: Float = 0f
    private var stickyCenterX: Float = 0.5f
    private var stickyCenterY: Float = 0.5f
    private var hits: Int = 0
    private var misses: Int = 0
    private var lastEmitMs: Long = 0L

    fun reset() {
        stickyCategory = null
        stickySpanish = null
        stickySide = ObjectSide.CENTER
        stickyScore = 0f
        stickyArea = 0f
        stickyCenterX = 0.5f
        stickyCenterY = 0.5f
        hits = 0
        misses = 0
        lastEmitMs = 0L
    }

    /**
     * @return detección estable lista para anunciar, o null si aún no es fiable.
     */
    fun update(
        raw: List<PedestrianDetection>,
        nowMs: Long = System.currentTimeMillis(),
    ): PedestrianDetection? {
        val candidate = PedestrianObjectMapper.pickPrimary(
            raw.filter { it.score >= CONFIRM_SCORE && it.areaRatio >= MIN_AREA },
        )

        if (candidate == null) {
            misses++
            if (misses >= MISS_CLEAR) {
                stickyCategory = null
                hits = 0
            }
            return null
        }

        misses = 0
        val same = stickyCategory == candidate.category
        if (same) {
            hits++
            stickyScore = stickyScore * 0.6f + candidate.score * 0.4f
            stickyArea = stickyArea * 0.6f + candidate.areaRatio * 0.4f
            stickyCenterX = stickyCenterX * 0.65f + candidate.centerXNorm * 0.35f
            stickyCenterY = stickyCenterY * 0.65f + candidate.centerYNorm * 0.35f
            stickySide = hysteresisSide(stickySide, stickyCenterX)
            stickySpanish = candidate.spanish
        } else {
            // Cambio de categoría: reinicia contador (evita saltos persona↔coche)
            stickyCategory = candidate.category
            stickySpanish = candidate.spanish
            stickySide = candidate.side
            stickyScore = candidate.score
            stickyArea = candidate.areaRatio
            stickyCenterX = candidate.centerXNorm
            stickyCenterY = candidate.centerYNorm
            hits = 1
            return null
        }

        if (hits < HITS_REQUIRED) return null
        if (nowMs - lastEmitMs < MIN_EMIT_GAP_MS) return null

        lastEmitMs = nowMs
        return PedestrianDetection(
            category = stickyCategory ?: return null,
            spanish = stickySpanish ?: return null,
            score = stickyScore,
            side = stickySide,
            areaRatio = stickyArea,
            centerXNorm = stickyCenterX,
            centerYNorm = stickyCenterY,
        )
    }

    private fun hysteresisSide(current: ObjectSide, cx: Float): ObjectSide {
        return when (current) {
            ObjectSide.LEFT -> when {
                cx > 0.42f && cx < 0.58f -> ObjectSide.CENTER
                cx >= 0.58f -> ObjectSide.RIGHT
                else -> ObjectSide.LEFT
            }
            ObjectSide.RIGHT -> when {
                cx > 0.42f && cx < 0.58f -> ObjectSide.CENTER
                cx <= 0.42f -> ObjectSide.LEFT
                else -> ObjectSide.RIGHT
            }
            ObjectSide.CENTER -> when {
                cx < 0.28f -> ObjectSide.LEFT
                cx > 0.72f -> ObjectSide.RIGHT
                else -> ObjectSide.CENTER
            }
        }
    }

    companion object {
        private const val CONFIRM_SCORE = 0.42f
        private const val MIN_AREA = 0.018f
        private const val HITS_REQUIRED = 4
        private const val MISS_CLEAR = 5
        private const val MIN_EMIT_GAP_MS = 2_800L
    }
}
