package com.youtubly.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A personal note pinned to a moment of a video ("12:40 - key point about ..."). */
@Entity(tableName = "video_notes", indices = [Index(value = ["videoId"])])
data class VideoNoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoId: String,
    val videoUrl: String,
    val videoTitle: String,
    val uploaderName: String,
    val positionMs: Long,
    val text: String,
    val createdAt: Long
)
