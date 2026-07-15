package io.lazaro.pathguide

enum class OpeningType {
    DOOR,
    ARCH,
    CORRIDOR_END,
}

enum class JunctionType {
    NONE,
    T_LEFT,
    T_RIGHT,
    T_BOTH,
    DEAD_END,
}

enum class ApproachPhase {
    FAR,
    APPROACHING,
    CLOSE,
    VERY_CLOSE,
}

enum class ExitSide {
    LEFT,
    RIGHT,
    CENTER,
    UNKNOWN,
}

enum class ExitBrainPhase {
    EXPLORE,
    EXIT_FOUND,
    ALIGN,
    CENTERED,
    PASS,
    BLOCKED,
}

data class OpeningCandidate(
    val type: OpeningType = OpeningType.DOOR,
    val detected: Boolean = false,
    val confidence: Float = 0f,
    val leftJambNorm: Float = 0f,
    val rightJambNorm: Float = 1f,
    val centerNorm: Float = 0.5f,
    val openingWidthNorm: Float = 0f,
    val approachFactor: Float = 0f,
    val leftProximity: Float = 0f,
    val rightProximity: Float = 0f,
    val isCentered: Boolean = true,
) {
    fun toDoorwayState(): DoorwayState {
        return DoorwayState(
            detected = detected,
            confidence = confidence,
            leftJambNorm = leftJambNorm,
            rightJambNorm = rightJambNorm,
            centerNorm = centerNorm,
            openingWidthNorm = openingWidthNorm,
            approachFactor = approachFactor,
            leftProximity = leftProximity,
            rightProximity = rightProximity,
            isCentered = isCentered,
        )
    }
}

data class ApproachState(
    val phase: ApproachPhase = ApproachPhase.FAR,
    val velocity: Float = 0f,
    val stableMs: Long = 0L,
    val announceReady: Boolean = false,
)

data class ExitTarget(
    val side: ExitSide = ExitSide.UNKNOWN,
    val opening: OpeningCandidate? = null,
    val junction: JunctionType = JunctionType.NONE,
)

data class ExitBrainFrameResult(
    val phase: ExitBrainPhase = ExitBrainPhase.EXPLORE,
    val leftBeep: Float = 0f,
    val rightBeep: Float = 0f,
    val doorwayMode: Boolean = false,
    val continuousTone: Boolean = false,
    val voiceCue: DoorwayVoiceCue? = null,
    val isExitGuiding: Boolean = false,
    val junctionType: JunctionType = JunctionType.NONE,
    val approachState: ApproachState = ApproachState(),
    val shouldAnnounceFrontal: Boolean = false,
    val frontalBlocksExit: Boolean = false,
    val doorwayPhase: DoorwayGuidePhase = DoorwayGuidePhase.IDLE,
    val turnRemainingDeg: Float? = null,
    val turnTurnedDeg: Float? = null,
    val suppressScene: Boolean = false,
    val exitTarget: ExitTarget = ExitTarget(),
    val outdoorPhase: OutdoorNavPhase? = null,
    val roadSide: RoadSide = RoadSide.UNKNOWN,
    val safeSide: RoadSide = RoadSide.UNKNOWN,
    val sidewalkAlignment: SidewalkAlignment = SidewalkAlignment.UNKNOWN,
    val mapsInstructionType: MapsInstructionType = MapsInstructionType.OTHER,
    val warningMode: Boolean = false,
)
