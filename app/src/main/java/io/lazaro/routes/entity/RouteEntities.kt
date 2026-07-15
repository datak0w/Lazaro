package io.lazaro.routes.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "saved_routes")
data class SavedRoute(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val destinationKey: String? = null,
    val destinationLabel: String? = null,
    val endLat: Double = 0.0,
    val endLng: Double = 0.0,
    val startLat: Double = 0.0,
    val startLng: Double = 0.0,
    val canonicalPolyline: String = "",
    val canonicalProfileJson: String = "",
    val runCount: Int = 0,
    val qualityScore: Float = 0f,
    val totalLengthM: Float = 0f,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "route_runs",
    indices = [Index("routeId")],
)
data class RouteRun(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routeId: Long,
    val startedAt: Long,
    val endedAt: Long = 0L,
    val sampleCount: Int = 0,
    val gpsQuality: Float = 0f,
)

@Entity(
    tableName = "route_observations",
    indices = [Index("runId"), Index(value = ["runId", "seq"])],
)
data class RouteObservation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: Long,
    val seq: Int,
    val timestampMs: Long,
    val lat: Double,
    val lng: Double,
    val accuracyM: Float = 0f,
    val bearingDeg: Float = 0f,
    val yawDeg: Float = 0f,
    val leftP: Float = 0f,
    val centerP: Float = 0f,
    val rightP: Float = 0f,
    val safeSide: String = "UNKNOWN",
    val roadSide: String = "UNKNOWN",
    val junction: String = "NONE",
    val phase: String = "EXPLORE",
    val segmentType: String = "unknown",
    val obstacleLabel: String? = null,
    val distanceAlongM: Float = 0f,
)

@Entity(
    tableName = "heatmap_cells",
    indices = [Index(value = ["routeId", "gridIndex"], unique = true)],
)
data class HeatmapCell(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routeId: Long,
    val gridIndex: Int,
    val distanceAlongM: Float = 0f,
    val meanLeft: Float = 0f,
    val meanRight: Float = 0f,
    val meanCenter: Float = 0f,
    val safeSide: String = "UNKNOWN",
    val lateralVariance: Float = 0f,
    val obstacleHits: Int = 0,
    val confidence: Float = 0f,
    val segmentType: String = "unknown",
)

@Entity(
    tableName = "route_memory_links",
    indices = [Index("memoryKeyOrAlias"), Index("routeId")],
)
data class RouteMemoryLink(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val memoryKeyOrAlias: String,
    val routeId: Long,
)

@Entity(
    tableName = "route_segments",
    indices = [Index("routeId")],
)
data class RouteSegment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routeId: Long,
    val name: String,
    val startGridIndex: Int,
    val endGridIndex: Int,
    val segmentType: String,
)
