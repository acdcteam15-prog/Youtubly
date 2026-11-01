package com.youtubly.app.di

import android.content.Context
import androidx.room.Room
import com.youtubly.app.data.local.YoutublyDatabase
import com.youtubly.app.data.local.dao.DownloadDao
import com.youtubly.app.data.local.dao.SearchHistoryDao
import com.youtubly.app.data.local.dao.SubscriptionCacheDao
import com.youtubly.app.data.local.dao.WatchHistoryDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun provideDatabase(@ApplicationContext context: Context): YoutublyDatabase =
        Room.databaseBuilder(context, YoutublyDatabase::class.java, "youtubly.db")
            // Real migrations first so upgrades keep history and downloads; the destructive
            // fallback only applies to versions we have no migration for (pre-v4 dev builds).
            .addMigrations(YoutublyDatabase.MIGRATION_4_5, YoutublyDatabase.MIGRATION_5_6, YoutublyDatabase.MIGRATION_6_7, YoutublyDatabase.MIGRATION_7_8, YoutublyDatabase.MIGRATION_8_9, YoutublyDatabase.MIGRATION_9_10, YoutublyDatabase.MIGRATION_10_11, YoutublyDatabase.MIGRATION_11_12)
            .fallbackToDestructiveMigrationFrom(1, 2, 3)
            .build()

    @Provides fun provideDownloadDao(db: YoutublyDatabase): DownloadDao = db.downloadDao()
    @Provides fun provideWatchHistoryDao(db: YoutublyDatabase): WatchHistoryDao = db.watchHistoryDao()
    @Provides fun provideSubscriptionCacheDao(db: YoutublyDatabase): SubscriptionCacheDao = db.subscriptionCacheDao()
    @Provides fun provideVideoNoteDao(db: YoutublyDatabase): com.youtubly.app.data.local.dao.VideoNoteDao = db.videoNoteDao()
    @Provides fun providePlaylistDao(db: YoutublyDatabase): com.youtubly.app.data.local.dao.PlaylistDao = db.playlistDao()
    @Provides fun provideEditJobDao(db: YoutublyDatabase): com.youtubly.app.data.local.dao.EditJobDao = db.editJobDao()
    @Provides fun provideSearchHistoryDao(db: YoutublyDatabase): SearchHistoryDao = db.searchHistoryDao()
}
