package com.youtubly.app.domain.repository

import com.youtubly.app.domain.model.ChannelWatchStat
import com.youtubly.app.domain.model.ExploreCategory
import com.youtubly.app.domain.model.HistoryRestore
import com.youtubly.app.domain.model.HistoryEntry
import com.youtubly.app.domain.model.StreamInfoResult
import com.youtubly.app.domain.model.Video
import kotlinx.coroutines.flow.Flow

interface VideoRepository {
    suspend fun getStreamInfo(videoUrl: String): Result<StreamInfoResult>
    suspend fun getExplore(category: ExploreCategory): Result<List<Video>>
    suspend fun recordWatched(video: Video)
    fun observeHistory(): Flow<List<Video>>

    /** Whole history, newest first, filtered by title/channel when [query] isn't blank. */
    fun observeHistoryEntries(query: String): Flow<List<HistoryEntry>>
    suspend fun removeFromHistory(videoId: String)
    suspend fun clearHistory()

    /** Removes several entries at once; the result can be handed to [restoreHistory] (Undo). */
    suspend fun removeFromHistory(videoIds: Collection<String>): HistoryRestore
    suspend fun restoreHistory(restore: HistoryRestore)

    /** Lower-cased channel name → local watch totals. */
    fun observeChannelStats(): Flow<Map<String, ChannelWatchStat>>

    /** Last saved position for a video, in ms (0 if never watched). */
    suspend fun savedPosition(videoId: String): Long
    suspend fun savePosition(videoId: String, positionMs: Long)
    /** videoId → fraction watched (0..1), for thumbnail progress bars. */
    fun observeProgress(): Flow<Map<String, Float>>
}
