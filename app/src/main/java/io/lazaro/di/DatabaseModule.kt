package io.lazaro.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.lazaro.memory.LazaroDatabase
import io.lazaro.memory.dao.LocationDao
import io.lazaro.memory.dao.MemoryDao
import io.lazaro.memory.dao.ProposalDao
import io.lazaro.memory.dao.SkillDao
import io.lazaro.messaging.dao.MessageDao
import io.lazaro.routes.dao.RouteDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): LazaroDatabase {
        return Room.databaseBuilder(
            context,
            LazaroDatabase::class.java,
            "lazaro_client.db",
        ).fallbackToDestructiveMigration().build()
    }

    @Provides fun provideMemoryDao(db: LazaroDatabase): MemoryDao = db.memoryDao()
    @Provides fun provideSkillDao(db: LazaroDatabase): SkillDao = db.skillDao()
    @Provides fun provideLocationDao(db: LazaroDatabase): LocationDao = db.locationDao()
    @Provides fun provideProposalDao(db: LazaroDatabase): ProposalDao = db.proposalDao()
    @Provides fun provideMessageDao(db: LazaroDatabase): MessageDao = db.messageDao()
    @Provides fun provideRouteDao(db: LazaroDatabase): RouteDao = db.routeDao()
}
