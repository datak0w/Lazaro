package io.lazaro.memory

import androidx.room.Database
import androidx.room.RoomDatabase
import io.lazaro.memory.dao.LocationDao
import io.lazaro.memory.dao.MemoryDao
import io.lazaro.memory.dao.ProposalDao
import io.lazaro.memory.dao.SkillDao
import io.lazaro.messaging.dao.MessageDao
import io.lazaro.messaging.entity.IncomingMessage
import io.lazaro.memory.entity.CustomSkill
import io.lazaro.memory.entity.LocationRecord
import io.lazaro.memory.entity.MemoryEntry
import io.lazaro.memory.entity.PendingMemoryProposal

@Database(
    entities = [
        MemoryEntry::class,
        CustomSkill::class,
        LocationRecord::class,
        PendingMemoryProposal::class,
        IncomingMessage::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class LazaroDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao
    abstract fun skillDao(): SkillDao
    abstract fun locationDao(): LocationDao
    abstract fun proposalDao(): ProposalDao
    abstract fun messageDao(): MessageDao
}
