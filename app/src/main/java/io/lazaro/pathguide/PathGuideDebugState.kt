package io.lazaro.pathguide

import android.graphics.Bitmap

data class PathGuideDebugState(
    val frame: Bitmap,
    val frameWidth: Int,
    val frameHeight: Int,
    val mode: PathGuideMode,
    val corridor: CorridorState,
    val doorwayPhase: DoorwayGuidePhase = DoorwayGuidePhase.IDLE,
    val stairState: StairState,
    val handrailSide: HandrailSide,
    val lastLabel: String? = null,
    val lastSceneDescription: String? = null,
    val turnRemainingDeg: Float? = null,
    val turnTurnedDeg: Float? = null,
    val brainPhase: ExitBrainPhase = ExitBrainPhase.EXPLORE,
    val junctionType: JunctionType = JunctionType.NONE,
    val approachState: ApproachState = ApproachState(),
    val outdoorPhase: OutdoorNavPhase? = null,
    val roadSide: RoadSide = RoadSide.UNKNOWN,
    val safeSide: RoadSide = RoadSide.UNKNOWN,
    val mapsInstructionType: MapsInstructionType = MapsInstructionType.OTHER,
    val stairPeaks: Int = 0,
    val updatedAtMs: Long = System.currentTimeMillis(),
)
