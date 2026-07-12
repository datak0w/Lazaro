package io.lazaro.messaging.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import io.lazaro.messaging.entity.IncomingMessage

@Dao
interface MessageDao {
    @Insert
    suspend fun insert(message: IncomingMessage): Long

    @Query("SELECT * FROM incoming_messages WHERE read = 0 ORDER BY timestamp ASC")
    suspend fun getUnread(): List<IncomingMessage>

    @Query("SELECT COUNT(*) FROM incoming_messages WHERE read = 0")
    suspend fun countUnread(): Int

    @Query("UPDATE incoming_messages SET read = 1 WHERE read = 0")
    suspend fun markAllRead()

    @Query("SELECT * FROM incoming_messages ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<IncomingMessage>

    @Query("DELETE FROM incoming_messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM incoming_messages")
    suspend fun deleteAll()
}
