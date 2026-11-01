package com.youtubly.app.presentation.search

import com.youtubly.app.core.SearchText
import com.youtubly.app.data.local.dao.PlaylistDao
import com.youtubly.app.data.local.entity.VideoNoteEntity
import com.youtubly.app.data.repository.NotesRepository
import com.youtubly.app.data.local.dao.SubscriptionCacheDao
import com.youtubly.app.domain.model.DownloadItem
import com.youtubly.app.domain.model.DownloadStatus
import com.youtubly.app.domain.model.Video
import com.youtubly.app.domain.repository.DownloadRepository
import com.youtubly.app.domain.repository.VideoRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** A saved video found in one of your playlists. */
data class PlaylistHit(val video: Video, val playlistId: Long, val playlistName: String)

/** A playlist whose name matches. */
data class PlaylistNameHit(val id: Long, val name: String)

/** A subscribed channel whose name matches (from the local cache; no network). */
data class ChannelHit(val channelId: String, val name: String) {
    val url: String get() = "https://www.youtube.com/channel/$channelId"
}

/** Everything on the device that matches a query — shown as "In your library". */
data class LibraryResults(
    val query: String = "",
    val channels: List<ChannelHit> = emptyList(),
    val playlists: List<PlaylistNameHit> = emptyList(),
    val history: List<Video> = emptyList(),
    val saved: List<PlaylistHit> = emptyList(),
    val downloads: List<DownloadItem> = emptyList(),
    val notes: List<VideoNoteEntity> = emptyList()
) {
    val isEmpty: Boolean
        get() = channels.isEmpty() && playlists.isEmpty() && history.isEmpty() && saved.isEmpty() && downloads.isEmpty() && notes.isEmpty()
    val count: Int get() = channels.size + playlists.size + history.size + saved.size + downloads.size + notes.size
}

/**
 * Searches your own data — subscriptions, playlists, watch history and downloads — in one
 * go. All sources are local (Room / DataStore), so this is fast enough to run while typing.
 */
class LibrarySearch @Inject constructor(
    private val videoRepository: VideoRepository,
    private val downloadRepository: DownloadRepository,
    private val playlistDao: PlaylistDao,
    private val subscriptionCache: SubscriptionCacheDao,
    private val notesRepository: NotesRepository
) {
    suspend fun search(raw: String, perSource: Int = 5): LibraryResults = coroutineScope {
        val q = raw.trim()
        if (q.length < 2) return@coroutineScope LibraryResults(query = q)

        val channels = async {
            runCatching { subscriptionCache.getAll() }.getOrDefault(emptyList())
                .filter { SearchText.matches(it.channelName, q) }
                // Names that start with the query first, then alphabetical (Arabic spelling variants count as equal).
                .sortedWith(compareBy({ !SearchText.normalize(it.channelName).startsWith(SearchText.normalize(q)) }, { it.channelName.lowercase() }))
                .take(perSource)
                .map { ChannelHit(it.channelId, it.channelName) }
        }
        val playlists = async {
            playlistDao.allPlaylists().filter { SearchText.matches(it.name, q) }.take(perSource).map { PlaylistNameHit(it.id, it.name) }
        }
        val saved = async {
            val names = playlistDao.allPlaylists().associate { it.id to it.name }
            playlistDao.allItems().filter { SearchText.matches(it.title, q) || SearchText.matches(it.uploaderName, q) }
                .distinctBy { it.videoId }          // same video in several playlists → once
                .take(perSource)
                .map { item ->
                    PlaylistHit(
                        Video(
                            id = item.videoId, url = item.url, title = item.title, uploaderName = item.uploaderName,
                            uploaderUrl = item.uploaderUrl, thumbnailUrl = item.thumbnailUrl, duration = item.durationSec,
                            viewCount = -1, uploadDate = null
                        ),
                        item.playlistId,
                        names[item.playlistId] ?: "Playlist"
                    )
                }
        }
        val history = async {
            videoRepository.observeHistoryEntries("").first()
                .map { it.video }
                .filter { SearchText.matches(it.title, q) || SearchText.matches(it.uploaderName, q) }
                .take(perSource)
        }
        val downloads = async {
            downloadRepository.observeDownloads().first()
                .filter { it.status == DownloadStatus.COMPLETED && SearchText.matches(it.title, q) }
                .take(perSource)
        }

        val notes = async { NotesRepository.filter(notesRepository.allNotes().first(), q).take(perSource) }
        val savedHits = saved.await()
        val savedIds = savedHits.mapTo(HashSet()) { it.video.id }
        LibraryResults(
            query = q,
            channels = channels.await(),
            playlists = playlists.await(),
            // A video that's both watched and saved shows once, under "Saved".
            history = history.await().filterNot { it.id in savedIds },
            saved = savedHits,
            downloads = downloads.await(),
            notes = notes.await()
        )
    }
}
