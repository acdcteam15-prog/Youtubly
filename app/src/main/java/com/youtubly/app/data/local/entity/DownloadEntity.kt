package com.youtubly.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val id: String,
    val videoId: String,
    val title: String,
    val thumbnailUrl: String?,
    val filePath: String?,
    val progress: Int,
    val status: String,
    val isAudioOnly: Boolean,
    val createdAt: Long,
    /** Watch page URL, so pause/resume/retry can re-resolve fresh stream URLs (they expire). */
    val videoUrl: String? = null,
    /** Which stream was chosen: "v|<resolution>|<format>" or "a|<bitrate>|<format>". */
    val formatKey: String? = null,
    /** Shown in the list, e.g. "720p · MPEG_4" or "128 kbps · M4A". */
    val qualityLabel: String? = null,
    @ColumnInfo(defaultValue = "0") val totalBytes: Long = 0,
    @ColumnInfo(defaultValue = "0") val downloadedBytes: Long = 0,
    /** Furthest point reached when played offline, 0..100. */
    @ColumnInfo(defaultValue = "0") val watchedPercent: Int = 0,
    /** Channel URL, so offline playback honours the blur "excluded channels" list. Null on
     *  downloads made before v9 — filled in on first online play. */
    val uploaderUrl: String? = null,
    /** Exact resume point of offline playback (ms) and the file's duration (ms). Saved every few seconds. */
    @ColumnInfo(defaultValue = "0") val lastPositionMs: Long = 0,
    @ColumnInfo(defaultValue = "0") val lastDurationMs: Long = 0
)
