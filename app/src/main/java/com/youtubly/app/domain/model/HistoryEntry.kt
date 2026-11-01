package com.youtubly.app.domain.model

/** One row of the History screen. */
data class HistoryEntry(
    val video: Video,
    val watchedAt: Long,
    /** 0..1 watched, 0 when unknown. */
    val progress: Float
)

/** A watch-history row as stored, kept so a bulk delete can be undone exactly. */
data class HistoryRecord(
    val videoId: String,
    val url: String,
    val title: String,
    val uploaderName: String,
    val thumbnailUrl: String?,
    val lastPositionMs: Long,
    val durationSec: Long,
    val watchedAt: Long
)

/** What a bulk history delete removed; pass it back to restore everything. */
data class HistoryRestore(val records: List<HistoryRecord>) {
    val count: Int get() = records.size
}

/** Local viewing totals for one channel (matched by channel name). */
data class ChannelWatchStat(val name: String, val videos: Int, val watchedMs: Long, val lastWatchedAt: Long)
