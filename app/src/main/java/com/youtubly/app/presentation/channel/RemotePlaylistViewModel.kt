package com.youtubly.app.presentation.channel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.youtubly.app.core.readable
import com.youtubly.app.data.local.PlaylistIndex
import com.youtubly.app.data.local.dao.WatchHistoryDao
import com.youtubly.app.domain.channel.PlaylistProgress
import com.youtubly.app.data.repository.PlaylistRepository
import com.youtubly.app.domain.model.RemotePlaylist
import com.youtubly.app.domain.model.Video
import com.youtubly.app.domain.playlist.PlaylistOrder
import com.youtubly.app.domain.repository.ChannelRepository
import com.youtubly.app.download.PlaylistDownloader
import com.youtubly.app.playback.PlayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

data class RemotePlaylistUi(
    val loading: Boolean = true,
    val error: String? = null,
    val playlist: RemotePlaylist? = null,
    val videos: List<Video> = emptyList(),
    val next: Any? = null,
    val loadingMore: Boolean = false,
    /** Fetching the remaining pages for Play all / Shuffle / Save / Download / search. */
    val preparing: Boolean = false,
    val searchOpen: Boolean = false,
    val query: String = ""
)

/**
 * A YouTube playlist (from a channel's Playlists tab): browse, search inside it, Play all / Shuffle / Continue,
 * tap a video to play from there, save it to your own playlists, or download all of it.
 */
@HiltViewModel
class RemotePlaylistViewModel @Inject constructor(
    private val channelRepository: ChannelRepository,
    private val playerController: PlayerController,
    private val playlistRepository: PlaylistRepository,
    private val playlistIndex: PlaylistIndex,
    private val downloader: PlaylistDownloader,
    private val watchHistoryDao: WatchHistoryDao
) : ViewModel() {

    private val _ui = MutableStateFlow(RemotePlaylistUi())
    val ui: StateFlow<RemotePlaylistUi> = _ui
    private var loaded: String? = null

    /** One page fetch at a time: two overlapping fetches of the same page would look like "no new videos" and end paging early. */
    private val pagingLock = Mutex()

    /** Short status lines ("Saved 61 videos…") for the screen to toast. */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    val downloadProgress: StateFlow<PlaylistDownloader.Progress?> = downloader.progress

    fun load(url: String, force: Boolean = false) {
        if (!force && loaded == url && _ui.value.playlist != null) return
        loaded = url
        _ui.value = RemotePlaylistUi(loading = true)
        viewModelScope.launch {
            channelRepository.getPlaylist(url).fold(
                onSuccess = { p ->
                    _ui.value = RemotePlaylistUi(loading = false, playlist = p, videos = p.first.items, next = p.first.next)
                    persistIndex()
                    // Opened before: load the rest quietly so "Continue" can point past the first page.
                    if (playlistIndex.idsFor(p.url).size > p.first.items.size && p.first.next != null) {
                        ensureAll(quiet = true)
                    }
                    Unit
                },
                onFailure = { _ui.value = RemotePlaylistUi(loading = false, error = it.readable()) }
            )
        }
    }

    fun loadMore() {
        val cur = _ui.value
        if (cur.playlist == null || cur.next == null || cur.loadingMore || cur.preparing) return
        _ui.value = cur.copy(loadingMore = true)
        viewModelScope.launch {
            pagingLock.withLock {
                val s = _ui.value
                val p = s.playlist
                val token = s.next
                if (p == null || token == null) { _ui.value = s.copy(loadingMore = false); return@withLock }
                channelRepository.morePlaylistVideos(p.url, token).fold(
                    onSuccess = { page -> _ui.value = _ui.value.merge(page.items, page.next); persistIndex() },
                    onFailure = { _ui.value = _ui.value.copy(loadingMore = false, next = null) }
                )
            }
        }
    }

    /** Remaining pages, up to [MAX_QUEUE] videos. Returns everything loaded. [quiet] = don't disable the buttons. */
    private suspend fun ensureAll(quiet: Boolean = false): List<Video> {
        // Flag first, so the buttons react at once even if a background load still holds the lock.
        if (!quiet) _ui.value = _ui.value.copy(preparing = true)
        try {
            return pagingLock.withLock {
                val p = _ui.value.playlist ?: return@withLock emptyList()
                while (true) {
                    val s = _ui.value
                    val token = s.next ?: break
                    if (s.videos.size >= MAX_QUEUE) break
                    val page = channelRepository.morePlaylistVideos(p.url, token).getOrNull() ?: break
                    _ui.value = _ui.value.merge(page.items, page.next)
                }
                persistIndex()
                _ui.value.videos
            }
        } finally {
            if (!quiet) _ui.value = _ui.value.copy(preparing = false)
        }
    }

    private fun persistIndex() {
        val s = _ui.value
        val p = s.playlist ?: return
        playlistIndex.save(p.url, s.videos.map { it.id })
    }

    /**
     * Sets up the player queue and hands the video to open to [onReady]. Fetches the remaining pages first
     * so next / previous work across the whole playlist, not just what was scrolled to.
     * [startAt] = tapped video; with [shuffle] it plays first and the rest are shuffled.
     */
    fun startQueue(shuffle: Boolean, startAt: Video? = null, onReady: (Video) -> Unit) {
        val p = _ui.value.playlist ?: return
        if (_ui.value.preparing) return
        viewModelScope.launch {
            val list = ensureAll()
            if (list.isEmpty()) return@launch
            val ordered = if (shuffle) PlaylistOrder.shuffled(list, startAt) else list
            val index = if (shuffle) 0 else startAt?.let { s -> list.indexOfFirst { it.id == s.id } }?.coerceAtLeast(0) ?: 0
            playerController.setQueue(if (shuffle) "${p.title} · shuffled" else p.title, ordered, index)
            onReady(ordered[index])
        }
    }

    /**
     * Copies the whole playlist into your own playlists (on this device). With [autoRemoveFinished] on, a video
     * leaves the copy once it's been watched, and the playlist moves to the Finished shelf when the last one is done.
     */
    fun saveToLibrary(autoRemoveFinished: Boolean) {
        val p = _ui.value.playlist ?: return
        if (_ui.value.preparing) return
        viewModelScope.launch {
            val list = ensureAll()
            if (list.isEmpty()) return@launch
            playlistRepository.saveAll(p.title, list, autoRemoveFinished)
            // Videos you've already watched are moved out right away by the auto-remove watcher; tell the user.
            val alreadyWatched = if (autoRemoveFinished) {
                val progress = watchHistoryDao.progressFor(list.map { it.id })
                progress.count { it.durationSec > 0 && PlaylistProgress.isWatched(it.lastPositionMs / 1000f / it.durationSec) }
            } else 0
            _messages.tryEmit(
                buildString {
                    append("Saved ${list.size} videos to your playlists")
                    if (alreadyWatched > 0) append(" · $alreadyWatched already watched will be removed")
                }
            )
        }
    }

    /** Queues every video for download at [quality]; the Wi-Fi / battery / overnight rules apply to each. */
    fun downloadAll(quality: PlaylistDownloader.Quality) {
        val p = _ui.value.playlist ?: return
        if (_ui.value.preparing) return
        viewModelScope.launch {
            val list = ensureAll()
            if (list.isEmpty()) return@launch
            val started = downloader.start(p.title, list, quality)
            _messages.tryEmit(if (started) "Queuing ${list.size} downloads…" else "Another playlist is still being queued")
        }
    }

    fun cancelDownloads() = downloader.cancel()

    fun openSearch() {
        _ui.value = _ui.value.copy(searchOpen = true)
        // Searching needs the whole list; load the rest in the background.
        if (_ui.value.next != null) viewModelScope.launch { ensureAll(quiet = true) }
    }

    fun closeSearch() { _ui.value = _ui.value.copy(searchOpen = false, query = "") }
    fun setQuery(q: String) { _ui.value = _ui.value.copy(query = q) }

    private fun RemotePlaylistUi.merge(more: List<Video>, nextToken: Any?): RemotePlaylistUi {
        val merged = (videos + more).distinctBy { it.id }
        // No new videos -> stop paging (avoids looping on a repeated token).
        return copy(videos = merged, next = nextToken.takeIf { merged.size > videos.size }, loadingMore = false)
    }

    private companion object { const val MAX_QUEUE = 500 }
}
