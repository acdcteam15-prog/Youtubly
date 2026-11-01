package com.youtubly.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.youtubly.app.data.local.dao.DownloadDao
import com.youtubly.app.data.local.dao.EditJobDao
import com.youtubly.app.data.local.dao.PlaylistDao
import com.youtubly.app.data.local.dao.SearchHistoryDao
import com.youtubly.app.data.local.dao.SubscriptionCacheDao
import com.youtubly.app.data.local.dao.WatchHistoryDao
import com.youtubly.app.data.local.entity.DownloadEntity
import com.youtubly.app.data.local.entity.EditJobEntity
import com.youtubly.app.data.local.entity.PlaylistEntity
import com.youtubly.app.data.local.entity.PlaylistFinishedItemEntity
import com.youtubly.app.data.local.entity.PlaylistItemEntity
import com.youtubly.app.data.local.entity.SearchHistoryEntity
import com.youtubly.app.data.local.entity.SubscriptionEntity
import com.youtubly.app.data.local.entity.WatchHistoryEntity

@Database(
    entities = [DownloadEntity::class, WatchHistoryEntity::class, SubscriptionEntity::class, SearchHistoryEntity::class,
        PlaylistEntity::class, PlaylistItemEntity::class, PlaylistFinishedItemEntity::class, EditJobEntity::class],
    version = 11,
    exportSchema = false
)
abstract class YoutublyDatabase : RoomDatabase() {
    companion object {
        /** v5: download manager columns (quality, bytes, resume info, offline watch progress). */
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN videoUrl TEXT")
                db.execSQL("ALTER TABLE downloads ADD COLUMN formatKey TEXT")
                db.execSQL("ALTER TABLE downloads ADD COLUMN qualityLabel TEXT")
                db.execSQL("ALTER TABLE downloads ADD COLUMN totalBytes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE downloads ADD COLUMN downloadedBytes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE downloads ADD COLUMN watchedPercent INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v6: local playlists. SQL mirrors the entities exactly so Room's schema check passes. */
        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `playlists` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `playlist_items` (" +
                        "`playlistId` INTEGER NOT NULL, `videoId` TEXT NOT NULL, `position` INTEGER NOT NULL, " +
                        "`url` TEXT NOT NULL, `title` TEXT NOT NULL, `uploaderName` TEXT NOT NULL, " +
                        "`uploaderUrl` TEXT, `thumbnailUrl` TEXT, `durationSec` INTEGER NOT NULL, " +
                        "`addedAt` INTEGER NOT NULL, PRIMARY KEY(`playlistId`, `videoId`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_playlist_items_playlistId_position` " +
                        "ON `playlist_items` (`playlistId`, `position`)"
                )
            }
        }
        /** v7: video editor export queue. SQL mirrors EditJobEntity exactly. */
        val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `edit_jobs` (" +
                        "`id` TEXT NOT NULL, `sourceDownloadId` TEXT NOT NULL, `sourcePath` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, `thumbnailUrl` TEXT, `sourceDurationMs` INTEGER NOT NULL, " +
                        "`startMs` INTEGER NOT NULL, `endMs` INTEGER NOT NULL, `status` TEXT NOT NULL, " +
                        "`progress` INTEGER NOT NULL, `outputUri` TEXT, `outputName` TEXT, `error` TEXT, " +
                        "`createdAt` INTEGER NOT NULL, `finishedAt` INTEGER, PRIMARY KEY(`id`))"
                )
            }
        }

        /** v8: export spec (cuts, merge, compression). Nullable, no default — matches the entity. */
        val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `edit_jobs` ADD COLUMN `spec` TEXT")
            }
        }

        /** v9: channel of each download (blur exclusions offline). Nullable, no default. */
        val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `downloads` ADD COLUMN `uploaderUrl` TEXT")
            }
        }

        /** v11: auto-remove-finished playlists, their Finished shelf, and the table of moved-out videos. */
        val MIGRATION_10_11 = object : androidx.room.migration.Migration(10, 11) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `playlists` ADD COLUMN `autoRemoveFinished` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `playlists` ADD COLUMN `finishedAt` INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `playlist_finished_items` (" +
                        "`playlistId` INTEGER NOT NULL, `videoId` TEXT NOT NULL, `position` INTEGER NOT NULL, " +
                        "`url` TEXT NOT NULL, `title` TEXT NOT NULL, `uploaderName` TEXT NOT NULL, " +
                        "`uploaderUrl` TEXT, `thumbnailUrl` TEXT, `durationSec` INTEGER NOT NULL, " +
                        "`addedAt` INTEGER NOT NULL, `finishedAt` INTEGER NOT NULL, PRIMARY KEY(`playlistId`, `videoId`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_playlist_finished_items_playlistId` " +
                        "ON `playlist_finished_items` (`playlistId`)"
                )
            }
        }

        /** v10: exact offline resume position + duration per download. */
        val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `downloads` ADD COLUMN `lastPositionMs` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `downloads` ADD COLUMN `lastDurationMs` INTEGER NOT NULL DEFAULT 0")
            }
        }
    }

    abstract fun playlistDao(): PlaylistDao
    abstract fun editJobDao(): EditJobDao
    abstract fun downloadDao(): DownloadDao
    abstract fun watchHistoryDao(): WatchHistoryDao
    abstract fun subscriptionCacheDao(): SubscriptionCacheDao
    abstract fun searchHistoryDao(): SearchHistoryDao
}
