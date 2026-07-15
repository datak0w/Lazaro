package io.lazaro.pathguide

data class SceneLabel(
    val spanish: String,
    val confidence: Float,
    val raw: String,
)

data class SceneLabels(
    val items: List<SceneLabel> = emptyList(),
    val primary: String? = null,
) {
    fun primaryOrDefault(): String = primary ?: "obstáculo"
}

data class FrontalObstacleState(
    val blocked: Boolean = false,
    val severity: Float = 0f,
    val closeRange: Boolean = false,
)

data class SceneSnapshot(
    val labels: SceneLabels,
    val corridor: CorridorState,
    val stairDetected: Boolean,
    val doorwayActive: Boolean,
    val doorwayPhase: DoorwayGuidePhase,
    val frontal: FrontalObstacleState,
)
