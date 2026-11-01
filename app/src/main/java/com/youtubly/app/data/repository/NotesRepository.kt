package com.youtubly.app.data.repository

import com.youtubly.app.core.SearchText
import com.youtubly.app.data.local.dao.VideoNoteDao
import com.youtubly.app.data.local.entity.VideoNoteEntity
import com.youtubly.app.domain.model.Video
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Timestamped notes on videos, stored on this device. */
@Singleton
class NotesRepository @Inject constructor(private val dao: VideoNoteDao) {
    fun notesFor(videoId: String): Flow<List<VideoNoteEntity>> = dao.observeFor(videoId)
    fun allNotes(): Flow<List<VideoNoteEntity>> = dao.observeAll()

    suspend fun add(video: Video, positionMs: Long, text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        dao.insert(
            VideoNoteEntity(
                videoId = video.id, videoUrl = video.url, videoTitle = video.title, uploaderName = video.uploaderName,
                positionMs = positionMs.coerceAtLeast(0), text = clean, createdAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun edit(id: Long, text: String) { text.trim().takeIf { it.isNotEmpty() }?.let { dao.updateText(id, it) } }
    suspend fun delete(id: Long) = dao.delete(id)

    companion object {
        /** Notes whose text, video title or channel contain [query] (Arabic-aware: diacritics and letter variants ignored). */
        fun filter(notes: List<VideoNoteEntity>, query: String): List<VideoNoteEntity> {
            val q = SearchText.normalize(query)
            if (q.isBlank()) return notes
            return notes.filter {
                SearchText.normalize(it.text).contains(q) || SearchText.normalize(it.videoTitle).contains(q) ||
                    SearchText.normalize(it.uploaderName).contains(q)
            }
        }
    }
}
