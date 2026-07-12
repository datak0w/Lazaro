package io.lazaro.memory.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "memory_entries")
data class MemoryEntry(
    @PrimaryKey val key: String,
    val value: String,
    val category: String,
    val aliases: String = "",
    val notes: String = "",
    val source: String = "user_stated",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

object MemoryCategory {
    const val ADDRESS = "address"
    const val CONTACT = "contact"
    const val PREFERENCE = "preference"
    const val PLACE = "place"
    const val CUSTOM = "custom"
}
