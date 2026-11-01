package com.youtubly.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.youtubly.app.data.local.entity.ChannelWatchStatRow
import com.youtubly.app.data.local.entity.WatchHistoryEntity
import com.youtubly.app.data.local.entity.WatchProgressRow
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchHistoryDao {
    @Query("SELECT * FROM watch_history ORDER BY watchedAt DESC LIMIT 100")
    fun observeHistory(): Flow<List<WatchHistoryEntity>>

    @Query("SELECT videoId, lastPositionMs, durationSec FROM watch_history WHERE lastPositionMs > 0")
    fun observeProgress(): Flow<List<WatchProgressRow>>

    @Query("SELECT * FROM watch_history WHERE videoId = :videoId LIMIT 1")
    suspend fun get(videoId: String): WatchHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WatchHistoryEntity)

    @Query("UPDATE watch_history SET lastPositionMs = :positionMs, watchedAt = :now WHERE videoId = :videoId")
    suspend fun updatePosition(videoId: String, positionMs: Long, now: Long)

    @Query("DELETE FROM watch_history")
    suspend fun clear()

    /** Most recent partly-watched entries, for the widget's "Continue watching". */
    @Query("SELECT * FROM watch_history WHERE lastPositionMs > 10000 ORDER BY watchedAt DESC LIMIT :limit")
    suspend fun recentInProgress(limit: Int): List<WatchHistoryEntity>

    /** Full history for the History screen, optionally filtered by title/channel. */
    @Query(
        "SELECT * FROM watch_history WHERE (:query = '' OR title LIKE '%' || :query || '%' OR uploaderName LIKE '%' || :query || '%') " +
            "ORDER BY watchedAt DESC"
    )
    fun search(query: String): Flow<List<WatchHistoryEntity>>

    @Query("DELETE FROM watch_history WHERE videoId = :videoId")
    suspend fun delete(videoId: String)

    /** Position update that leaves watchedAt alone (used while history is paused). */
    @Query("UPDATE watch_history SET lastPositionMs = :positionMs WHERE videoId = :videoId")
    suspend fun updatePositionOnly(videoId: String, positionMs: Long)

    @Query("SELECT videoId, lastPositionMs, durationSec FROM watch_history WHERE videoId IN (:ids)")
    suspend fun progressFor(ids: List<String>): List<WatchProgressRow>

    // ---------------- Bulk cleanup (History screen) ----------------

    @Query("SELECT * FROM watch_history WHERE videoId IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<WatchHistoryEntity>

    @Query("DELETE FROM watch_history WHERE videoId IN (:ids)")
    suspend fun deleteIds(ids: List<String>)

    /** Puts rows back exactly as they were (Undo after a bulk delete). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<WatchHistoryEntity>)

    /**
     * Per-channel totals for the Subscriptions screen. History stores the channel *name*
     * (no id), so channels are matched by name. Time watched is capped at each video's length.
     */
    @Query(
        "SELECT uploaderName AS name, COUNT(*) AS videos, " +
            "SUM(CASE WHEN durationSec > 0 THEN MIN(lastPositionMs, durationSec * 1000) ELSE lastPositionMs END) AS watchedMs, " +
            "MAX(watchedAt) AS lastWatchedAt " +
            "FROM watch_history GROUP BY uploaderName"
    )
    fun observeChannelStats(): Flow<List<ChannelWatchStatRow>>
}
