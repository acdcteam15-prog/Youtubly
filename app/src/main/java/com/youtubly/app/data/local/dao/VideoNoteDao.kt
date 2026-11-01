package com.youtubly.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.youtubly.app.data.local.entity.VideoNoteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoNoteDao {
    @Query("SELECT * FROM video_notes WHERE videoId = :videoId ORDER BY positionMs, id")
    fun observeFor(videoId: String): Flow<List<VideoNoteEntity>>

    @Query("SELECT * FROM video_notes ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<VideoNoteEntity>>

    @Insert
    suspend fun insert(note: VideoNoteEntity): Long

    @Query("UPDATE video_notes SET text = :text WHERE id = :id")
    suspend fun updateText(id: Long, text: String)

    @Query("DELETE FROM video_notes WHERE id = :id")
    suspend fun delete(id: Long)
}
