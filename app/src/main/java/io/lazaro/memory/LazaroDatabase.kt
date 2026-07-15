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
import io.lazaro.routes.dao.RouteDao
import io.lazaro.routes.entity.HeatmapCell
import io.lazaro.routes.entity.RouteMemoryLink
import io.lazaro.routes.entity.RouteObservation
import io.lazaro.routes.entity.RouteRun
import io.lazaro.routes.entity.RouteSegment
import io.lazaro.routes.entity.SavedRoute

@Database(
    entities = [
        MemoryEntry::class,
        CustomSkill::class,
        LocationRecord::class,
        PendingMemoryProposal::class,
        IncomingMessage::class,
        SavedRoute::class,
        RouteRun::class,
        RouteObservation::class,
        HeatmapCell::class,
        RouteMemoryLink::class,
        RouteSegment::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class LazaroDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao
    abstract fun skillDao(): SkillDao
    abstract fun locationDao(): LocationDao
    abstract fun proposalDao(): ProposalDao
    abstract fun messageDao(): MessageDao
    abstract fun routeDao(): RouteDao
}
