package io.lazaro.memory.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "location_records")
data class LocationRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val latitude: Double,
    val longitude: Double,
    val address: String? = null,
    val label: String? = null,
    val source: String = "periodic",
    val visitedAt: Long = System.currentTimeMillis(),
)
