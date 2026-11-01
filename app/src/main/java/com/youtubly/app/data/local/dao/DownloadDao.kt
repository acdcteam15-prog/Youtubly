package com.youtubly.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.youtubly.app.data.local.entity.DownloadEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DownloadEntity)

    @Query("UPDATE downloads SET progress = :progress, status = :status WHERE id = :id")
    suspend fun updateProgress(id: String, progress: Int, status: String)

    @Query("UPDATE downloads SET status = :status, filePath = :filePath, progress = 100 WHERE id = :id")
    suspend fun markCompleted(id: String, status: String, filePath: String)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE downloads SET uploaderUrl = :uploaderUrl WHERE id = :id")
    suspend fun setUploaderUrl(id: String, uploaderUrl: String)

    @Query("SELECT * FROM downloads WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): DownloadEntity?

    @Query("SELECT * FROM downloads")
    suspend fun getAll(): List<DownloadEntity>

    @Query("UPDATE downloads SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    /** Progress from a running worker. Ignored once the row is PAUSED (or gone): a worker that
     *  hasn't noticed its cancellation yet mustn't flip a paused download back to RUNNING. */
    @Query("UPDATE downloads SET progress = :progress, status = 'RUNNING', downloadedBytes = :downloaded, totalBytes = :total WHERE id = :id AND status IN ('QUEUED', 'RUNNING')")
    suspend fun updateRunning(id: String, progress: Int, downloaded: Long, total: Long)

    @Query("UPDATE downloads SET status = 'FAILED' WHERE id = :id AND status IN ('QUEUED', 'RUNNING')")
    suspend fun markFailedIfActive(id: String)

    /** New/resumed transfer: remember the chosen stream so later resumes pick the same one. */
    @Query("UPDATE downloads SET status = 'QUEUED', formatKey = :formatKey, qualityLabel = :label WHERE id = :id")
    suspend fun markQueued(id: String, formatKey: String?, label: String?)

    /** Records where the in-progress file lives, so a paused download can resume into it. */
    @Query("UPDATE downloads SET filePath = :filePath WHERE id = :id")
    suspend fun updateFilePath(id: String, filePath: String)

    @Query("UPDATE downloads SET watchedPercent = MAX(watchedPercent, :percent) WHERE id = :id")
    suspend fun updateWatched(id: String, percent: Int)

    /** Resume point; also keeps the "furthest point watched" percent that drives Watched / auto-delete. */
    @Query("UPDATE downloads SET lastPositionMs = :positionMs, lastDurationMs = :durationMs, watchedPercent = MAX(watchedPercent, :percent) WHERE id = :id")
    suspend fun updatePosition(id: String, positionMs: Long, durationMs: Long, percent: Int)

    /** Videos played to 90 %+ offline — counts as "finished" for auto-remove playlists. */
    @Query("SELECT DISTINCT videoId FROM downloads WHERE watchedPercent >= 90")
    fun observeWatchedVideoIds(): kotlinx.coroutines.flow.Flow<List<String>>

    /** "Restart playlist": forget offline progress so the videos don't count as finished again. */
    @Query("UPDATE downloads SET watchedPercent = 0, lastPositionMs = 0 WHERE videoId IN (:videoIds)")
    suspend fun resetWatched(videoIds: List<String>)
}
