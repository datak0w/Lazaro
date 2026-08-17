package io.lazaro.pathguide

/**
 * Detección peatonal local (MediaPipe Object Detector, categorías COCO).
 * Lista reducida a lo útil en calle: menos falsos positivos (silla, botella…).
 */
enum class ObjectSide {
    LEFT,
    CENTER,
    RIGHT,
}

data class PedestrianDetection(
    val category: String,
    val spanish: String,
    val score: Float,
    val side: ObjectSide,
    val areaRatio: Float,
    val centerXNorm: Float,
    val centerYNorm: Float,
) {
    val isFrontal: Boolean
        get() = side == ObjectSide.CENTER && areaRatio >= PedestrianObjectMapper.FRONTAL_AREA_MIN

    val phrase: String
        get() = PedestrianObjectMapper.phrase(spanish, side)
}

object PedestrianObjectMapper {

    const val FRONTAL_AREA_MIN = 0.045f

    /** Solo categorías fiables en acera / tráfico. */
    val allowedCategories: List<String> = listOf(
        "person",
        "bicycle",
        "car",
        "motorcycle",
        "bus",
        "truck",
        "dog",
        "traffic light",
        "stop sign",
        "bench",
    )

    private val spanishByCategory = mapOf(
        "person" to "persona",
        "bicycle" to "bicicleta",
        "car" to "coche",
        "motorcycle" to "moto",
        "bus" to "autobús",
        "truck" to "camión",
        "traffic light" to "semáforo",
        "stop sign" to "stop",
        "dog" to "perro",
        "bench" to "banco",
    )

    private val priority = mapOf(
        "person" to 100,
        "car" to 95,
        "truck" to 94,
        "bus" to 93,
        "motorcycle" to 90,
        "bicycle" to 85,
        "dog" to 80,
        "stop sign" to 70,
        "traffic light" to 68,
        "bench" to 40,
    )

    fun spanishLabel(category: String): String? {
        val key = category.trim().lowercase()
        return spanishByCategory[key]
    }

    fun sideFromBox(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        imageWidth: Float,
        imageHeight: Float,
    ): ObjectSide {
        val w = imageWidth.coerceAtLeast(1f)
        val cx = ((left + right) * 0.5f) / w
        return when {
            cx < 0.32f -> ObjectSide.LEFT
            cx > 0.68f -> ObjectSide.RIGHT
            else -> ObjectSide.CENTER
        }
    }

    fun areaRatio(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        imageWidth: Float,
        imageHeight: Float,
    ): Float {
        val bw = (right - left).coerceAtLeast(0f)
        val bh = (bottom - top).coerceAtLeast(0f)
        val iw = imageWidth.coerceAtLeast(1f)
        val ih = imageHeight.coerceAtLeast(1f)
        return ((bw * bh) / (iw * ih)).coerceIn(0f, 1f)
    }

    fun fromBox(
        category: String,
        score: Float,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        imageWidth: Float,
        imageHeight: Float,
    ): PedestrianDetection? {
        val spanish = spanishLabel(category) ?: return null
        val w = imageWidth.coerceAtLeast(1f)
        val h = imageHeight.coerceAtLeast(1f)
        val cx = ((left + right) * 0.5f) / w
        val cy = ((top + bottom) * 0.5f) / h
        return PedestrianDetection(
            category = category.trim().lowercase(),
            spanish = spanish,
            score = score,
            side = sideFromBox(left, top, right, bottom, imageWidth, imageHeight),
            areaRatio = areaRatio(left, top, right, bottom, imageWidth, imageHeight),
            centerXNorm = cx,
            centerYNorm = cy,
        )
    }

    fun phrase(spanish: String, side: ObjectSide): String {
        val noun = spanish.trim().lowercase()
        return when (side) {
            ObjectSide.LEFT -> "$noun a la izquierda"
            ObjectSide.RIGHT -> "$noun a la derecha"
            ObjectSide.CENTER -> "$noun delante"
        }
    }

    fun isHighPriority(category: String): Boolean {
        return when (category.trim().lowercase()) {
            "person", "car", "truck", "bus", "motorcycle", "bicycle", "dog" -> true
            else -> false
        }
    }

    fun pickPrimary(detections: List<PedestrianDetection>): PedestrianDetection? {
        if (detections.isEmpty()) return null
        return detections.maxWithOrNull(
            compareBy<PedestrianDetection> { if (it.isFrontal) 1 else 0 }
                .thenBy { if (isHighPriority(it.category)) 1 else 0 }
                .thenBy { priority[it.category] ?: 0 }
                .thenBy { it.areaRatio }
                .thenBy { it.score },
        )
    }

    fun frontalBeepBoost(detections: List<PedestrianDetection>): Float {
        val frontal = detections.filter { it.isFrontal }
        if (frontal.isEmpty()) return 0f
        val best = frontal.maxOf { it.areaRatio }
        return ((best - FRONTAL_AREA_MIN) / 0.22f).coerceIn(0f, 1f)
            .let { 0.18f + it * 0.55f }
    }
}
