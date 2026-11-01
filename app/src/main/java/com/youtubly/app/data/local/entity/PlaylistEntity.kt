package com.youtubly.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A local (on-device) playlist. Not synced with YouTube. */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** When on, a video moves out of the list (to [PlaylistFinishedItemEntity]) once it has been watched. */
    @ColumnInfo(defaultValue = "0") val autoRemoveFinished: Boolean = false,
    /** Set when the last video of an auto-remove playlist was finished: the playlist sits on the "Finished" shelf. */
    val finishedAt: Long? = null
)

/**
 * One video in a playlist. Title, channel, thumbnail and duration are copied in so a list
 * displays fully offline and survives the video going private.
 */
@Entity(
    tableName = "playlist_items",
    primaryKeys = ["playlistId", "videoId"],
    indices = [Index(value = ["playlistId", "position"])]
)
data class PlaylistItemEntity(
    val playlistId: Long,
    val videoId: String,
    val position: Int,
    val url: String,
    val title: String,
    val uploaderName: String,
    val uploaderUrl: String?,
    val thumbnailUrl: String?,
    val durationSec: Long,
    val addedAt: Long
)

/**
 * A video that was finished and moved out of an auto-remove playlist. Kept (not deleted) so the Finished shelf can
 * list it, and so "Restart" / "Restore" can put it back at its old [position].
 */
@Entity(
    tableName = "playlist_finished_items",
    primaryKeys = ["playlistId", "videoId"],
    indices = [Index(value = ["playlistId"])]
)
data class PlaylistFinishedItemEntity(
    val playlistId: Long,
    val videoId: String,
    val position: Int,
    val url: String,
    val title: String,
    val uploaderName: String,
    val uploaderUrl: String?,
    val thumbnailUrl: String?,
    val durationSec: Long,
    val addedAt: Long,
    val finishedAt: Long
)

/** Grid row: playlist plus its video count and cover (first item's thumbnail). */
data class PlaylistSummary(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val videoCount: Int,
    val coverUrl: String?,
    val autoRemoveFinished: Boolean = false,
    val finishedAt: Long? = null,
    /** Videos already finished and moved out (0 unless auto-remove is on). */
    val finishedCount: Int = 0
) {
    val isFinished: Boolean get() = finishedAt != null
}
