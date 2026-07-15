package io.lazaro.routes.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import io.lazaro.routes.entity.HeatmapCell
import io.lazaro.routes.entity.RouteMemoryLink
import io.lazaro.routes.entity.RouteObservation
import io.lazaro.routes.entity.RouteRun
import io.lazaro.routes.entity.RouteSegment
import io.lazaro.routes.entity.SavedRoute

@Dao
interface RouteDao {
    @Insert
    suspend fun insertRoute(route: SavedRoute): Long

    @Update
    suspend fun updateRoute(route: SavedRoute)

    @Query("SELECT * FROM saved_routes ORDER BY updatedAt DESC")
    suspend fun getAllRoutes(): List<SavedRoute>

    @Query("SELECT * FROM saved_routes WHERE id = :id LIMIT 1")
    suspend fun getRoute(id: Long): SavedRoute?

    @Query("DELETE FROM saved_routes WHERE id = :id")
    suspend fun deleteRoute(id: Long)

    @Insert
    suspend fun insertRun(run: RouteRun): Long

    @Update
    suspend fun updateRun(run: RouteRun)

    @Query("SELECT * FROM route_runs WHERE routeId = :routeId ORDER BY startedAt DESC")
    suspend fun getRunsForRoute(routeId: Long): List<RouteRun>

    @Query("SELECT * FROM route_runs WHERE id = :id LIMIT 1")
    suspend fun getRun(id: Long): RouteRun?

    @Insert
    suspend fun insertObservations(observations: List<RouteObservation>)

    @Query("SELECT * FROM route_observations WHERE runId = :runId ORDER BY seq ASC")
    suspend fun getObservationsForRun(runId: Long): List<RouteObservation>

    @Query("DELETE FROM route_observations WHERE runId = :runId")
    suspend fun deleteObservationsForRun(runId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHeatmapCells(cells: List<HeatmapCell>)

    @Query("SELECT * FROM heatmap_cells WHERE routeId = :routeId ORDER BY gridIndex ASC")
    suspend fun getHeatmapForRoute(routeId: Long): List<HeatmapCell>

    @Query("DELETE FROM heatmap_cells WHERE routeId = :routeId")
    suspend fun deleteHeatmapForRoute(routeId: Long)

    @Insert
    suspend fun insertMemoryLink(link: RouteMemoryLink): Long

    @Query("SELECT * FROM route_memory_links WHERE memoryKeyOrAlias = :key LIMIT 1")
    suspend fun getLinkByKey(key: String): RouteMemoryLink?

    @Query("SELECT * FROM route_memory_links WHERE routeId = :routeId")
    suspend fun getLinksForRoute(routeId: Long): List<RouteMemoryLink>

    @Query("DELETE FROM route_memory_links WHERE routeId = :routeId")
    suspend fun deleteLinksForRoute(routeId: Long)

    @Insert
    suspend fun insertSegments(segments: List<RouteSegment>)

    @Query("SELECT * FROM route_segments WHERE routeId = :routeId ORDER BY startGridIndex ASC")
    suspend fun getSegmentsForRoute(routeId: Long): List<RouteSegment>

    @Query("DELETE FROM route_segments WHERE routeId = :routeId")
    suspend fun deleteSegmentsForRoute(routeId: Long)

    @Transaction
    suspend fun deleteRouteCascade(routeId: Long) {
        val runs = getRunsForRoute(routeId)
        for (run in runs) {
            deleteObservationsForRun(run.id)
        }
        deleteHeatmapForRoute(routeId)
        deleteLinksForRoute(routeId)
        deleteSegmentsForRoute(routeId)
        deleteRoute(routeId)
    }
}
