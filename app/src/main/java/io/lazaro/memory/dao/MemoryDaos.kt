package io.lazaro.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.lazaro.memory.entity.CustomSkill
import io.lazaro.memory.entity.LocationRecord
import io.lazaro.memory.entity.MemoryEntry
import io.lazaro.memory.entity.PendingMemoryProposal

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memory_entries ORDER BY updatedAt DESC")
    suspend fun getAllMemories(): List<MemoryEntry>

    @Query("SELECT * FROM memory_entries WHERE key = :key LIMIT 1")
    suspend fun getMemory(key: String): MemoryEntry?

    @Query("SELECT * FROM memory_entries WHERE category = :category")
    suspend fun getByCategory(category: String): List<MemoryEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMemory(entry: MemoryEntry)

    @Query("DELETE FROM memory_entries WHERE key = :key")
    suspend fun deleteMemory(key: String)
}

@Dao
interface SkillDao {
    @Query("SELECT * FROM custom_skills WHERE confirmed = 1 ORDER BY useCount DESC")
    suspend fun getConfirmedSkills(): List<CustomSkill>

    @Query("SELECT * FROM custom_skills ORDER BY updatedAt DESC")
    suspend fun getAllSkills(): List<CustomSkill>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSkill(skill: CustomSkill): Long

    @Query("UPDATE custom_skills SET useCount = useCount + 1, updatedAt = :now WHERE id = :id")
    suspend fun incrementUseCount(id: Long, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM custom_skills WHERE id = :id")
    suspend fun deleteSkill(id: Long)
}

@Dao
interface LocationDao {
    @Query("SELECT * FROM location_records ORDER BY visitedAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<LocationRecord>

    @Query("SELECT * FROM location_records WHERE visitedAt >= :since ORDER BY visitedAt DESC")
    suspend fun getSince(since: Long): List<LocationRecord>

    @Insert
    suspend fun insert(record: LocationRecord): Long

    @Query("SELECT * FROM location_records WHERE label IS NOT NULL ORDER BY visitedAt DESC")
    suspend fun getLabeledPlaces(): List<LocationRecord>

    @Query("DELETE FROM location_records WHERE id = :id")
    suspend fun deleteLocation(id: Long)
}

@Dao
interface ProposalDao {
    @Query("SELECT * FROM pending_memory_proposals ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatest(): PendingMemoryProposal?

    @Insert
    suspend fun insert(proposal: PendingMemoryProposal): Long

    @Query("DELETE FROM pending_memory_proposals")
    suspend fun clearAll()
}
