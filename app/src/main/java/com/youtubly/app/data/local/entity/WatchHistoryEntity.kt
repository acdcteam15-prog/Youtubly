package com.youtubly.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "watch_history")
data class WatchHistoryEntity(
    @PrimaryKey val videoId: String,
    val url: String,
    val title: String,
    val uploaderName: String,
    val thumbnailUrl: String?,
    val lastPositionMs: Long,
    val durationSec: Long,
    val watchedAt: Long
)

/** Lightweight projection for thumbnail progress bars. */
data class WatchProgressRow(val videoId: String, val lastPositionMs: Long, val durationSec: Long)

/** Aggregate row: how much of one channel's content is in watch history. */
data class ChannelWatchStatRow(val name: String, val videos: Int, val watchedMs: Long, val lastWatchedAt: Long)
