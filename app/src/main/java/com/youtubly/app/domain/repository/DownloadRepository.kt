package com.youtubly.app.domain.repository

import com.youtubly.app.domain.model.DownloadItem
import com.youtubly.app.domain.model.DownloadOption
import com.youtubly.app.domain.model.DownloadStorage
import kotlinx.coroutines.flow.Flow

interface DownloadRepository {
    fun observeDownloads(): Flow<List<DownloadItem>>
    suspend fun enqueueDownload(videoId: String, videoUrl: String, title: String, thumbnailUrl: String?, option: DownloadOption, uploaderUrl: String? = null): String
    /** Channel of a download: stored value, else resolved online once and saved (null if offline). */
    suspend fun channelUrlFor(id: String): String?
    suspend fun deleteDownload(id: String)

    /** Stops the transfer and keeps the partial file. */
    suspend fun pause(id: String)
    /** Continues a paused or failed download, re-resolving fresh stream URLs first. */
    suspend fun resume(id: String): Result<Unit>

    suspend fun storage(): DownloadStorage
    /** Deletes completed downloads that are ≥95 % watched (except [excludeId]); returns how many. */
    suspend fun deleteWatched(excludeId: String? = null): Int
    /** Same as [deleteWatched] but only when the auto-delete setting is on. */
    suspend fun autoDeleteWatched(excludeId: String? = null): Int
    /** Offline playback progress for a download, 0..100. */
    suspend fun recordLocalProgress(id: String, percent: Int)
}
