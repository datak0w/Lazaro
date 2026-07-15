package io.lazaro.pathguide

class PathGuideAnalyzer(
    private val corridorScorer: CorridorScorer = CorridorScorer(),
    private val openingDetector: OpeningDetector = OpeningDetector(),
    private val frontalObstacleDetector: FrontalObstacleDetector = FrontalObstacleDetector(),
) {

    fun analyze(gray: ByteArray, width: Int, height: Int, sensitivity: Float): CorridorState {
        val opening = openingDetector.detect(gray, width, height)
        val doorway = opening.toDoorwayState()
        val corridor = corridorScorer.score(gray, width, height, sensitivity)
        val frontal = frontalObstacleDetector.detect(gray, width, height, sensitivity)

        val frontalBlocked = corridor.isFrontallyBlocked ||
            frontal.blocked ||
            (frontal.severity >= 0.30f && corridor.centerProximity >= 0.22f)

        if (!doorway.detected) {
            return corridor.copy(
                isFrontallyBlocked = frontalBlocked,
                frontalSeverity = frontal.severity,
                frontalCloseRange = frontal.closeRange,
            )
        }

        val blend = doorway.confidence.coerceIn(0f, 1f)
        val left = blend * doorway.leftProximity + (1f - blend) * corridor.leftProximity
        val right = blend * doorway.rightProximity + (1f - blend) * corridor.rightProximity

        return corridor.copy(
            leftProximity = left,
            rightProximity = right,
            isCentered = doorway.isCentered && left < 0.22f && right < 0.22f,
            isFrontallyBlocked = frontalBlocked && !doorway.detected,
            frontalSeverity = frontal.severity,
            frontalCloseRange = frontal.closeRange,
            doorwayActive = true,
            doorway = doorway,
        )
    }

    fun reset() {
        corridorScorer.reset()
        openingDetector.reset()
        frontalObstacleDetector.reset()
    }
}
