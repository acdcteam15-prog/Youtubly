package com.youtubly.app.data.repository

import com.youtubly.app.core.DispatcherProvider
import com.youtubly.app.core.safeIoCall
import com.youtubly.app.data.local.dao.WatchHistoryDao
import com.youtubly.app.data.local.entity.WatchHistoryEntity
import com.youtubly.app.data.remote.newpipe.StreamExtractorService
import com.youtubly.app.data.remote.newpipe.TrendingExtractorService
import com.youtubly.app.data.local.datastore.UserPreferences
import com.youtubly.app.domain.model.ExploreCategory
import com.youtubly.app.domain.model.ChannelWatchStat
import com.youtubly.app.domain.model.HistoryEntry
import com.youtubly.app.domain.model.HistoryRecord
import com.youtubly.app.domain.model.HistoryRestore
import com.youtubly.app.domain.model.StreamInfoResult
import com.youtubly.app.domain.model.Video
import com.youtubly.app.domain.repository.VideoRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class VideoRepositoryImpl @Inject constructor(
    private val streamExtractorService: StreamExtractorService,
    private val trendingExtractorService: TrendingExtractorService,
    private val watchHistoryDao: WatchHistoryDao,
    private val dispatchers: DispatcherProvider,
    private val userPreferences: UserPreferences
) : VideoRepository {

    override suspend fun getStreamInfo(videoUrl: String): Result<StreamInfoResult> =
        safeIoCall(dispatchers) { streamExtractorService.extractStreamInfo(videoUrl) }

    override suspend fun getExplore(category: ExploreCategory): Result<List<Video>> =
        safeIoCall(dispatchers) { trendingExtractorService.getKiosk(category.kioskId) }

    override suspend fun recordWatched(video: Video) {
        // "Pause history": nothing new is recorded and existing entries aren't bumped.
        if (com.youtubly.app.core.Incognito.isOn || userPreferences.pauseHistory.first()) return
        val existing = watchHistoryDao.get(video.id)
        watchHistoryDao.upsert(
            WatchHistoryEntity(
                videoId = video.id,
                url = video.url,
                title = video.title,
                uploaderName = video.uploaderName,
                thumbnailUrl = video.thumbnailUrl,
                lastPositionMs = existing?.lastPositionMs ?: 0L,
                durationSec = video.duration.takeIf { it > 0 } ?: existing?.durationSec ?: 0L,
                watchedAt = System.currentTimeMillis()
            )
        )
    }

    override fun observeHistory(): Flow<List<Video>> =
        watchHistoryDao.observeHistory().map { list ->
            list.map { it.toVideo() }
        }

    override suspend fun savedPosition(videoId: String): Long = watchHistoryDao.get(videoId)?.lastPositionMs ?: 0L

    override suspend fun savePosition(videoId: String, positionMs: Long) {
        // Incognito: leave no trace, not even an updated resume point on an older entry.
        if (com.youtubly.app.core.Incognito.isOn) return
        // Resume positions keep working while paused, but without moving the entry to the top.
        if (userPreferences.pauseHistory.first()) watchHistoryDao.updatePositionOnly(videoId, positionMs)
        else watchHistoryDao.updatePosition(videoId, positionMs, System.currentTimeMillis())
    }

    override fun observeHistoryEntries(query: String): Flow<List<HistoryEntry>> =
        watchHistoryDao.search(query.trim()).map { list ->
            list.map {
                HistoryEntry(
                    video = it.toVideo(),
                    watchedAt = it.watchedAt,
                    progress = if (it.durationSec > 0) (it.lastPositionMs / 1000f / it.durationSec).coerceIn(0f, 1f) else 0f
                )
            }
        }

    override suspend fun removeFromHistory(videoId: String) = watchHistoryDao.delete(videoId)

    override suspend fun clearHistory() = watchHistoryDao.clear()

    override suspend fun removeFromHistory(videoIds: Collection<String>): HistoryRestore {
        // SQLite caps bound parameters (999 on older devices), so work in chunks.
        val removed = videoIds.distinct().chunked(500).flatMap { ids ->
            val rows = watchHistoryDao.getByIds(ids)
            watchHistoryDao.deleteIds(ids)
            rows
        }
        return HistoryRestore(removed.map {
            HistoryRecord(it.videoId, it.url, it.title, it.uploaderName, it.thumbnailUrl, it.lastPositionMs, it.durationSec, it.watchedAt)
        })
    }

    override suspend fun restoreHistory(restore: HistoryRestore) {
        watchHistoryDao.upsertAll(restore.records.map {
            WatchHistoryEntity(it.videoId, it.url, it.title, it.uploaderName, it.thumbnailUrl, it.lastPositionMs, it.durationSec, it.watchedAt)
        })
    }

    override fun observeChannelStats(): Flow<Map<String, ChannelWatchStat>> =
        watchHistoryDao.observeChannelStats().map { rows ->
            rows.associate { it.name.trim().lowercase() to ChannelWatchStat(it.name, it.videos, it.watchedMs, it.lastWatchedAt) }
        }

    private fun WatchHistoryEntity.toVideo() = Video(
        id = videoId,
        url = url,
        title = title,
        uploaderName = uploaderName,
        uploaderUrl = null,
        thumbnailUrl = thumbnailUrl,
        duration = durationSec,
        viewCount = -1L,
        uploadDate = null
    )

    override fun observeProgress(): Flow<Map<String, Float>> =
        watchHistoryDao.observeProgress().map { rows ->
            rows.filter { it.durationSec > 0 }
                .associate { it.videoId to (it.lastPositionMs / 1000f / it.durationSec).coerceIn(0f, 1f) }
        }
}
