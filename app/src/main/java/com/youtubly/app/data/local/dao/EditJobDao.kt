package com.youtubly.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.youtubly.app.data.local.entity.EditJobEntity
import kotlinx.coroutines.flow.Flow

/**
 * Status changes are conditional (WHERE status …) so the worker and the UI can never
 * overwrite each other: e.g. a cancel that lands while the export finishes wins cleanly.
 */
@Dao
interface EditJobDao {
    @Query("SELECT * FROM edit_jobs ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<EditJobEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: EditJobEntity)

    @Query("SELECT * FROM edit_jobs WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): EditJobEntity?

    @Query("SELECT status FROM edit_jobs WHERE id = :id LIMIT 1")
    suspend fun statusOf(id: String): String?

    /** Oldest queued job first (FIFO). */
    @Query("SELECT * FROM edit_jobs WHERE status = 'QUEUED' ORDER BY createdAt ASC LIMIT 1")
    suspend fun nextQueued(): EditJobEntity?

    @Query("UPDATE edit_jobs SET status = 'RUNNING', progress = 0, error = NULL WHERE id = :id AND status = 'QUEUED'")
    suspend fun claim(id: String): Int

    /** A job left RUNNING by a killed process goes back to the queue. Only called by the
     *  queue worker, and only one queue worker ever runs at a time. */
    @Query("UPDATE edit_jobs SET status = 'QUEUED', progress = 0 WHERE status = 'RUNNING'")
    suspend fun requeueInterrupted(): Int

    @Query("UPDATE edit_jobs SET status = 'QUEUED', progress = 0 WHERE id = :id AND status = 'RUNNING'")
    suspend fun requeue(id: String): Int

    @Query("UPDATE edit_jobs SET progress = :progress WHERE id = :id AND status = 'RUNNING'")
    suspend fun updateProgress(id: String, progress: Int)

    @Query("UPDATE edit_jobs SET status = 'COMPLETED', progress = 100, outputUri = :uri, outputName = :name, finishedAt = :at WHERE id = :id AND status = 'RUNNING'")
    suspend fun markCompleted(id: String, uri: String, name: String, at: Long): Int

    @Query("UPDATE edit_jobs SET status = 'FAILED', error = :error, finishedAt = :at WHERE id = :id AND status = 'RUNNING'")
    suspend fun markFailed(id: String, error: String, at: Long): Int

    @Query("UPDATE edit_jobs SET status = 'CANCELLED', finishedAt = :at WHERE id = :id AND status IN ('QUEUED', 'RUNNING')")
    suspend fun cancel(id: String, at: Long): Int

    /** Back to the end of the queue. */
    @Query("UPDATE edit_jobs SET status = 'QUEUED', progress = 0, error = NULL, finishedAt = NULL, createdAt = :now WHERE id = :id AND status IN ('FAILED', 'CANCELLED')")
    suspend fun retry(id: String, now: Long): Int

    @Query("DELETE FROM edit_jobs WHERE id = :id AND status IN ('COMPLETED', 'FAILED', 'CANCELLED')")
    suspend fun removeFinished(id: String): Int

    @Query("DELETE FROM edit_jobs WHERE status IN ('COMPLETED', 'FAILED', 'CANCELLED')")
    suspend fun clearFinished(): Int

    @Query("SELECT COUNT(*) FROM edit_jobs WHERE status IN ('QUEUED', 'RUNNING')")
    suspend fun activeCount(): Int
}
