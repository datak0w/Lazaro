package io.lazaro.routes.fusion

import io.lazaro.routes.RouteRepository
import io.lazaro.routes.entity.HeatmapCell
import io.lazaro.routes.entity.RouteSegment
import io.lazaro.routes.model.CanonicalProfilePoint
import io.lazaro.routes.model.RouteCodec
import io.lazaro.routes.model.RouteSample
import io.lazaro.routes.model.toSample
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.roundToInt

@Singleton
class RouteCanonicalizer @Inject constructor(
    private val routeRepository: RouteRepository,
) {
    fun buildCanonical(
        samples: List<RouteSample>,
        gridStepM: Float = GRID_STEP_M,
    ): CanonicalResult {
        if (samples.size < RouteRepository.MIN_SAMPLES) {
            return CanonicalResult.Invalid("Muy pocas muestras. Camina un poco más y vuelve a grabar.")
        }

        val avgAccuracy = samples.map { it.accuracyM }.average().toFloat()
        if (avgAccuracy > RouteRepository.MAX_GPS_ACCURACY_M) {
            return CanonicalResult.Invalid(
                "Señal GPS muy débil (precisión media ${avgAccuracy.toInt()} m). Intenta en un tramo más abierto.",
            )
        }

        val polyline = samples.map { it.lat to it.lng }
        val totalLength = samples.lastOrNull()?.distanceAlongM ?: 0f
        val profile = resampleProfile(samples, totalLength, gridStepM)
        val segments = detectSegments(profile)

        return CanonicalResult.Success(
            polylineJson = RouteCodec.encodePolyline(polyline),
            profileJson = RouteCodec.encodeProfile(profile),
            profile = profile,
            totalLengthM = totalLength,
            qualityScore = (1f - (avgAccuracy / 40f)).coerceIn(0.2f, 1f),
            segments = segments,
            startLat = samples.first().lat,
            startLng = samples.first().lng,
            endLat = samples.last().lat,
            endLng = samples.last().lng,
        )
    }

    private fun resampleProfile(
        samples: List<RouteSample>,
        totalLength: Float,
        gridStepM: Float,
    ): List<CanonicalProfilePoint> {
        if (samples.isEmpty()) return emptyList()
        val cellCount = ((totalLength / gridStepM).roundToInt()).coerceAtLeast(1)
        return buildList {
            for (i in 0..cellCount) {
                val targetD = i * gridStepM
                val nearest = samples.minByOrNull { abs(it.distanceAlongM - targetD) } ?: continue
                add(
                    CanonicalProfilePoint(
                        distanceAlongM = targetD,
                        leftP = nearest.leftP,
                        centerP = nearest.centerP,
                        rightP = nearest.rightP,
                        safeSide = nearest.safeSide,
                        roadSide = nearest.roadSide,
                        yawDeg = nearest.yawDeg,
                        segmentType = nearest.segmentType,
                        lat = nearest.lat,
                        lng = nearest.lng,
                    ),
                )
            }
        }
    }

    private fun detectSegments(profile: List<CanonicalProfilePoint>): List<RouteSegmentDraft> {
        if (profile.isEmpty()) return emptyList()
        val segments = mutableListOf<RouteSegmentDraft>()
        var start = 0
        var currentType = profile.first().segmentType
        for (i in 1 until profile.size) {
            if (profile[i].segmentType != currentType) {
                segments += RouteSegmentDraft(
                    name = labelFor(currentType, segments.size),
                    startGridIndex = start,
                    endGridIndex = i - 1,
                    segmentType = currentType,
                )
                start = i
                currentType = profile[i].segmentType
            }
        }
        segments += RouteSegmentDraft(
            name = labelFor(currentType, segments.size),
            startGridIndex = start,
            endGridIndex = profile.lastIndex,
            segmentType = currentType,
        )
        return segments
    }

    private fun labelFor(type: String, index: Int): String = when (type) {
        "urban_sidewalk" -> "Acera urbana ${index + 1}"
        "rural_lane" -> "Camino rural ${index + 1}"
        "wooded" -> "Tramo arbolado ${index + 1}"
        else -> "Tramo ${index + 1}"
    }

    companion object {
        const val GRID_STEP_M = 2f
    }
}

sealed class CanonicalResult {
    data class Success(
        val polylineJson: String,
        val profileJson: String,
        val profile: List<CanonicalProfilePoint>,
        val totalLengthM: Float,
        val qualityScore: Float,
        val segments: List<RouteSegmentDraft>,
        val startLat: Double,
        val startLng: Double,
        val endLat: Double,
        val endLng: Double,
    ) : CanonicalResult()

    data class Invalid(val reason: String) : CanonicalResult()
}

data class RouteSegmentDraft(
    val name: String,
    val startGridIndex: Int,
    val endGridIndex: Int,
    val segmentType: String,
)

fun RouteSegmentDraft.toEntity(routeId: Long) = RouteSegment(
    routeId = routeId,
    name = name,
    startGridIndex = startGridIndex,
    endGridIndex = endGridIndex,
    segmentType = segmentType,
)

@Singleton
class RouteHeatmapBuilder @Inject constructor() {
    fun build(
        routeId: Long,
        canonicalProfile: List<CanonicalProfilePoint>,
        allRunSamples: List<List<RouteSample>>,
    ): List<HeatmapCell> {
        if (canonicalProfile.isEmpty()) return emptyList()

        return canonicalProfile.mapIndexed { gridIndex, canonical ->
            val aligned = allRunSamples.mapNotNull { run ->
                run.minByOrNull { abs(it.distanceAlongM - canonical.distanceAlongM) }
            }.filter { abs(it.distanceAlongM - canonical.distanceAlongM) <= 3f }

            val leftValues = aligned.map { it.leftP }
            val rightValues = aligned.map { it.rightP }
            val centerValues = aligned.map { it.centerP }

            val meanLeft = median(leftValues, canonical.leftP)
            val meanRight = median(rightValues, canonical.rightP)
            val meanCenter = median(centerValues, canonical.centerP)
            val lateralVariance = variance(leftValues + rightValues)
            val obstacleHits = aligned.count { !it.obstacleLabel.isNullOrBlank() }
            val confidence = (aligned.size.toFloat() / allRunSamples.size.coerceAtLeast(1))
                .coerceIn(0.1f, 1f) * (1f - lateralVariance.coerceIn(0f, 0.5f))

            HeatmapCell(
                routeId = routeId,
                gridIndex = gridIndex,
                distanceAlongM = canonical.distanceAlongM,
                meanLeft = meanLeft,
                meanRight = meanRight,
                meanCenter = meanCenter,
                safeSide = canonical.safeSide,
                lateralVariance = lateralVariance,
                obstacleHits = obstacleHits,
                confidence = confidence,
                segmentType = canonical.segmentType,
            )
        }
    }

    fun mergeProfiles(
        existing: List<CanonicalProfilePoint>,
        newProfile: List<CanonicalProfilePoint>,
        existingWeight: Float,
        newWeight: Float,
    ): List<CanonicalProfilePoint> {
        val maxSize = maxOf(existing.size, newProfile.size)
        if (maxSize == 0) return newProfile
        return buildList {
            for (i in 0 until maxSize) {
                val e = existing.getOrNull(i)
                val n = newProfile.getOrNull(i)
                when {
                    e == null && n != null -> add(n)
                    n == null && e != null -> add(e)
                    e != null && n != null -> add(
                        CanonicalProfilePoint(
                            distanceAlongM = n.distanceAlongM,
                            leftP = blend(e.leftP, n.leftP, existingWeight, newWeight),
                            centerP = blend(e.centerP, n.centerP, existingWeight, newWeight),
                            rightP = blend(e.rightP, n.rightP, existingWeight, newWeight),
                            safeSide = if (newWeight >= existingWeight) n.safeSide else e.safeSide,
                            roadSide = if (newWeight >= existingWeight) n.roadSide else e.roadSide,
                            yawDeg = blend(e.yawDeg, n.yawDeg, existingWeight, newWeight),
                            segmentType = if (newWeight >= existingWeight) n.segmentType else e.segmentType,
                            lat = if (newWeight >= existingWeight) n.lat else e.lat,
                            lng = if (newWeight >= existingWeight) n.lng else e.lng,
                        ),
                    )
                    else -> Unit
                }
            }
        }
    }

    private fun blend(a: Float, b: Float, wa: Float, wb: Float): Float {
        val total = wa + wb
        if (total <= 0f) return b
        return (a * wa + b * wb) / total
    }

    private fun median(values: List<Float>, fallback: Float): Float {
        if (values.isEmpty()) return fallback
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private fun variance(values: List<Float>): Float {
        if (values.size < 2) return 0f
        val mean = values.average().toFloat()
        return values.map { (it - mean) * (it - mean) }.average().toFloat()
    }
}

fun List<io.lazaro.routes.entity.RouteObservation>.toSamples(): List<RouteSample> =
    map { it.toSample() }
