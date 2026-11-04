package com.youtubly.app.data.repository

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.youtubly.app.core.Constants
import com.youtubly.app.core.DispatcherProvider
import com.youtubly.app.core.safeIoCall
import com.youtubly.app.data.local.dao.DownloadDao
import com.youtubly.app.data.local.dao.WatchHistoryDao
import com.youtubly.app.data.local.datastore.UserPreferences
import com.youtubly.app.data.local.entity.DownloadEntity
import com.youtubly.app.data.remote.newpipe.StreamExtractorService
import com.youtubly.app.domain.model.DownloadItem
import com.youtubly.app.domain.model.DownloadOption
import com.youtubly.app.domain.model.DownloadOptions
import com.youtubly.app.domain.model.DownloadStatus
import com.youtubly.app.domain.model.DownloadStorage
import com.youtubly.app.domain.repository.DownloadRepository
import com.youtubly.app.download.DownloadWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject

class DownloadRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadDao: DownloadDao,
    private val watchHistoryDao: WatchHistoryDao,
    private val workManager: WorkManager,
    private val streamExtractorService: StreamExtractorService,
    private val userPreferences: UserPreferences,
    private val dispatchers: DispatcherProvider
) : DownloadRepository {

    override fun observeDownloads(): Flow<List<DownloadItem>> =
        combine(downloadDao.observeAll(), watchHistoryDao.observeProgress()) { list, progress ->
            val online = progress.filter { it.durationSec > 0 }
                .associate { it.videoId to (it.lastPositionMs / 10f / it.durationSec).toInt().coerceIn(0, 100) }
            list.map { it.toItem(maxOf(it.watchedPercent, online[it.videoId] ?: 0)) }
        }

    private fun DownloadEntity.toItem(watched: Int) = DownloadItem(
        id = id,
        videoId = videoId,
        title = title,
        thumbnailUrl = thumbnailUrl,
        filePath = filePath,
        progress = progress,
        status = runCatching { DownloadStatus.valueOf(status) }.getOrDefault(DownloadStatus.FAILED),
        isAudioOnly = isAudioOnly,
        qualityLabel = qualityLabel,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        watchedPercent = watched,
        uploaderUrl = uploaderUrl,
        positionMs = lastPositionMs,
        durationMs = lastDurationMs,
        createdAt = createdAt
    )

    override suspend fun enqueueDownload(
        videoId: String,
        videoUrl: String,
        title: String,
        thumbnailUrl: String?,
        option: DownloadOption,
        uploaderUrl: String?
    ): String {
        val id = UUID.randomUUID().toString()
        downloadDao.upsert(
            DownloadEntity(
                id = id,
                videoId = videoId,
                title = title,
                thumbnailUrl = thumbnailUrl,
                filePath = null,
                progress = 0,
                status = DownloadStatus.QUEUED.name,
                isAudioOnly = option.audioOnly,
                createdAt = System.currentTimeMillis(),
                videoUrl = videoUrl,
                formatKey = option.formatKey,
                qualityLabel = option.label,
                uploaderUrl = uploaderUrl
            )
        )
        schedule(id, option, title, resume = false)
        return id
    }

    override suspend fun channelUrlFor(id: String): String? {
        val e = downloadDao.getById(id) ?: return null
        e.uploaderUrl?.let { return it }
        val url = e.videoUrl ?: return null
        val channel = safeIoCall(dispatchers) { streamExtractorService.extractStreamInfo(url) }
            .getOrNull()?.video?.uploaderUrl?.takeIf { it.isNotBlank() } ?: return null
        downloadDao.setUploaderUrl(id, channel)
        return channel
    }

    /** @param manual true when the user just pressed resume/retry: that means "now", so the overnight window is skipped. */
    private suspend fun schedule(id: String, option: DownloadOption, title: String, resume: Boolean, manual: Boolean = false) {
        val rules = userPreferences.downloadRules.first()
        val delayMs = if (manual) 0L else com.youtubly.app.domain.download.DownloadRules.delayUntilWindowMs(rules, System.currentTimeMillis())
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(
                workDataOf(
                    DownloadWorker.KEY_DOWNLOAD_ID to id,
                    DownloadWorker.KEY_STREAM_URL to option.url,
                    DownloadWorker.KEY_AUDIO_URL to option.audioUrl,
                    DownloadWorker.KEY_TITLE to title,
                    DownloadWorker.KEY_AUDIO_ONLY to option.audioOnly,
                    DownloadWorker.KEY_EXTENSION to option.extension,
                    DownloadWorker.KEY_RESUME to resume
                )
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (rules.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .build()
            )
            .apply { if (delayMs > 0) setInitialDelay(delayMs, java.util.concurrent.TimeUnit.MILLISECONDS) }
            // Low-battery pauses come back as retries; check again every 5 minutes.
            .setBackoffCriteria(androidx.work.BackoffPolicy.LINEAR, 5, java.util.concurrent.TimeUnit.MINUTES)
            .addTag(id)
            .build()
        // REPLACE: a resume must supersede whatever (cancelled) work is still registered.
        workManager.enqueueUniqueWork(id, ExistingWorkPolicy.REPLACE, request)
    }

    override suspend fun deleteDownload(id: String) {
        workManager.cancelUniqueWork(id)
        downloadDao.getById(id)?.filePath?.let { path ->
            withContext(dispatchers.io) {
                // HD downloads also leave parts while in progress.
                listOf(path, "$path.video.part", "$path.audio.part", "$path.muxing").forEach { runCatching { File(it).delete() } }
            }
        }
        downloadDao.delete(id)
    }

    override suspend fun pause(id: String) {
        // Status first: the worker's progress writes are conditional on QUEUED/RUNNING, so it
        // can't overwrite PAUSED while it winds down.
        downloadDao.updateStatus(id, DownloadStatus.PAUSED.name)
        workManager.cancelUniqueWork(id)
    }

    override suspend fun resume(id: String): Result<Unit> {
        val e = downloadDao.getById(id) ?: return Result.failure(IllegalStateException("Download not found"))
        val videoUrl = e.videoUrl ?: "https://www.youtube.com/watch?v=${e.videoId}"
        downloadDao.updateStatus(id, DownloadStatus.QUEUED.name)
        return safeIoCall(dispatchers) { streamExtractorService.extractStreamInfo(videoUrl) }.mapCatching { info ->
            val option = DownloadOptions.match(info, e.formatKey, e.isAudioOnly)
                ?: error("This video no longer has a downloadable stream")
            // Only append to the partial file if it's the very same stream as before.
            val sameStream = e.formatKey == null || option.formatKey == e.formatKey
            downloadDao.markQueued(id, option.formatKey, option.label)
            schedule(id, option, e.title, resume = sameStream, manual = true)
        }.onFailure { downloadDao.updateStatus(id, DownloadStatus.FAILED.name) }
    }

    override suspend fun storage(): DownloadStorage = withContext(dispatchers.io) {
        val dir = File(context.getExternalFilesDir(null), Constants.DOWNLOAD_DIR_NAME)
        val used = dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        val free = (if (dir.exists()) dir else dir.parentFile ?: dir).usableSpace
        DownloadStorage(used, free)
    }

    override suspend fun deleteWatched(excludeId: String?): Int {
        val items = observeDownloads().first()
        val watched = items.filter { it.status == DownloadStatus.COMPLETED && it.watchedPercent >= WATCHED_PERCENT && it.id != excludeId }
        watched.forEach { deleteDownload(it.id) }
        return watched.size
    }

    override suspend fun autoDeleteWatched(excludeId: String?): Int {
        val global = userPreferences.autoDeleteWatched.first()
        val rules = userPreferences.channelDefaults.first()
        // A channel can switch it on or off for itself, whatever the global switch says.
        val victims = com.youtubly.app.domain.download.AutoDelete.targets(
            observeDownloads().first(), excludeId, global, WATCHED_PERCENT
        ) { url -> com.youtubly.app.domain.playback.ChannelDefaults.lookup(rules, url)?.autoDeleteWatched }
        victims.forEach { deleteDownload(it.id) }
        return victims.size
    }

    override suspend fun recordLocalProgress(id: String, percent: Int) =
        downloadDao.updateWatched(id, percent.coerceIn(0, 100))

    companion object {
        const val WATCHED_PERCENT = 95
    }
}
