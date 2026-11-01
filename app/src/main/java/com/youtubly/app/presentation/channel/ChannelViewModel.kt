package com.youtubly.app.presentation.channel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.youtubly.app.core.readable
import com.youtubly.app.data.local.PlaylistIndex
import com.youtubly.app.data.local.datastore.UserPreferences
import com.youtubly.app.domain.channel.VideoSort
import com.youtubly.app.domain.model.Channel
import com.youtubly.app.domain.model.ChannelPlaylist
import com.youtubly.app.domain.model.ChannelTabKind
import com.youtubly.app.domain.model.Video
import com.youtubly.app.domain.repository.AccountRepository
import com.youtubly.app.domain.repository.ChannelRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ChannelUiState {
    data object Loading : ChannelUiState
    data class Success(val channel: Channel) : ChannelUiState
    data class Error(val message: String) : ChannelUiState
}

/** In-channel search. [videos] accumulate across pages. */
data class ChannelSearchState(
    val open: Boolean = false,
    val query: String = "",
    val submitted: String = "",
    val loading: Boolean = false,
    val videos: List<Video> = emptyList(),
    val continuation: String? = null,
    val error: String? = null
)

/** A tab's list: items so far, where the next page is (null = end), and paging status. */
data class ChannelFeed<T>(
    val items: List<T> = emptyList(),
    val next: Any? = null,
    val loading: Boolean = false,
    val error: String? = null,
    /** False until the first page was requested (only Videos comes with the channel; other tabs load when opened). */
    val started: Boolean = false
)

@HiltViewModel
class ChannelViewModel @Inject constructor(
    private val channelRepository: ChannelRepository,
    private val accountRepository: AccountRepository,
    userPreferences: UserPreferences,
    private val playlistIndex: PlaylistIndex
) : ViewModel() {

    private val _uiState = MutableStateFlow<ChannelUiState>(ChannelUiState.Loading)
    val uiState: StateFlow<ChannelUiState> = _uiState
    private var loaded: String? = null

    private val _tab = MutableStateFlow(ChannelTabKind.VIDEOS)
    val tab: StateFlow<ChannelTabKind> = _tab

    /** Videos / Live / Shorts lists, by tab. */
    private val _videoFeeds = MutableStateFlow<Map<ChannelTabKind, ChannelFeed<Video>>>(emptyMap())
    val videoFeeds: StateFlow<Map<ChannelTabKind, ChannelFeed<Video>>> = _videoFeeds

    private val _playlists = MutableStateFlow(ChannelFeed<ChannelPlaylist>())
    val playlists: StateFlow<ChannelFeed<ChannelPlaylist>> = _playlists

    private val _sort = MutableStateFlow(VideoSort.LATEST)
    val sort: StateFlow<VideoSort> = _sort

    /** Whether the optional Shorts tab is switched on in Settings. */
    val showShorts: StateFlow<Boolean> = userPreferences.showShortsTab
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _search = MutableStateFlow(ChannelSearchState())
    val search: StateFlow<ChannelSearchState> = _search
    private var searchJob: Job? = null
    private var loadAllJob: Job? = null

    fun load(channelUrl: String, force: Boolean = false) {
        if (!force && loaded == channelUrl && _uiState.value is ChannelUiState.Success) return
        if (loaded != channelUrl) closeSearch()
        loaded = channelUrl
        loadAllJob?.cancel()
        viewModelScope.launch {
            _uiState.value = ChannelUiState.Loading
            _tab.value = ChannelTabKind.VIDEOS
            _sort.value = VideoSort.LATEST
            _playlists.value = ChannelFeed()
            _videoFeeds.value = emptyMap()
            _uiState.value = channelRepository.getChannel(channelUrl).fold(
                onSuccess = { ch ->
                    _videoFeeds.value = mapOf(ChannelTabKind.VIDEOS to ChannelFeed(items = ch.videos, next = ch.videosNext, started = true))
                    ChannelUiState.Success(ch)
                },
                onFailure = { ChannelUiState.Error(it.message ?: "Couldn't load channel") }
            )
        }
    }

    private fun channel(): Channel? = (_uiState.value as? ChannelUiState.Success)?.channel
    private fun feedOf(tab: ChannelTabKind): ChannelFeed<Video> = _videoFeeds.value[tab] ?: ChannelFeed()
    private fun updateFeed(tab: ChannelTabKind, change: (ChannelFeed<Video>) -> ChannelFeed<Video>) {
        _videoFeeds.value = _videoFeeds.value + (tab to change(feedOf(tab)))
    }

    fun selectTab(tab: ChannelTabKind) {
        _tab.value = tab
        // Everything except the first Videos page is fetched the first time its tab is opened.
        when (tab) {
            ChannelTabKind.PLAYLISTS -> if (!_playlists.value.started) loadMorePlaylists()
            else -> if (!feedOf(tab).started) loadMoreVideos(tab)
        }
    }

    fun setSort(sort: VideoSort) { _sort.value = sort }

    /** Next page of whichever tab is showing. */
    fun loadMore() = when (val t = _tab.value) {
        ChannelTabKind.PLAYLISTS -> loadMorePlaylists()
        else -> loadMoreVideos(t)
    }

    private fun loadMoreVideos(tab: ChannelTabKind) {
        val ch = channel() ?: return
        val f = feedOf(tab)
        if (f.loading || (f.started && f.next == null && f.error == null)) return
        viewModelScope.launch { fetchVideosPage(ch, tab) }
    }

    private suspend fun fetchVideosPage(ch: Channel, tab: ChannelTabKind) {
        val before = feedOf(tab)
        if (before.loading) return
        updateFeed(tab) { it.copy(loading = true, error = null, started = true) }
        channelRepository.tabVideos(ch, tab, before.next).fold(
            onSuccess = { page ->
                updateFeed(tab) { cur ->
                    val merged = (cur.items + page.items).distinctBy { v -> v.id }
                    // No new videos -> stop paging (avoids looping on a repeated token).
                    cur.copy(items = merged, next = page.next.takeIf { merged.size > cur.items.size }, loading = false)
                }
            },
            onFailure = { e -> updateFeed(tab) { it.copy(loading = false, error = e.readable()) } }
        )
    }

    /**
     * Loads every remaining page of the open video tab (up to [MAX_ALL] videos), so that sorting by Popular or
     * Oldest covers the whole channel and not just what was scrolled to.
     */
    fun loadAll() {
        val ch = channel() ?: return
        val tab = _tab.value
        if (tab == ChannelTabKind.PLAYLISTS) return
        if (loadAllJob?.isActive == true) return
        loadAllJob = viewModelScope.launch {
            while (feedOf(tab).next != null && feedOf(tab).items.size < MAX_ALL) {
                fetchVideosPage(ch, tab)
                if (feedOf(tab).error != null) break
            }
        }
    }

    private fun loadMorePlaylists() {
        val ch = channel() ?: return
        val f = _playlists.value
        if (f.loading || (f.started && f.next == null && f.error == null)) return
        _playlists.value = f.copy(loading = true, error = null, started = true)
        viewModelScope.launch {
            channelRepository.playlists(ch, f.next).fold(
                onSuccess = { page ->
                    val cur = _playlists.value
                    val merged = (cur.items + page.items).distinctBy { p -> p.url }
                    _playlists.value = cur.copy(items = merged, next = page.next.takeIf { merged.size > cur.items.size }, loading = false)
                },
                onFailure = { e -> _playlists.value = _playlists.value.copy(loading = false, error = e.readable()) }
            )
        }
    }

    /** Retry after an error on the current tab. */
    fun retryTab() = loadMore()

    /** Videos this playlist had when last opened (empty until opened once) — for the "x of y watched" badge. */
    fun playlistVideoIds(playlistUrl: String): List<String> = playlistIndex.idsFor(playlistUrl)

    fun openSearch() { _search.value = _search.value.copy(open = true) }

    fun closeSearch() {
        searchJob?.cancel()
        _search.value = ChannelSearchState()
    }

    fun setQuery(q: String) { _search.value = _search.value.copy(query = q) }

    fun submitSearch() {
        val channel = channel() ?: return
        val q = _search.value.query.trim()
        if (q.isEmpty()) return
        searchJob?.cancel()
        _search.value = _search.value.copy(submitted = q, loading = true, videos = emptyList(), continuation = null, error = null)
        searchJob = viewModelScope.launch {
            channelRepository.searchChannel(channel, q).fold(
                onSuccess = { page -> _search.value = _search.value.copy(loading = false, videos = page.videos, continuation = page.continuation) },
                onFailure = { _search.value = _search.value.copy(loading = false, error = it.readable()) }
            )
        }
    }

    fun loadMoreResults() {
        val channel = channel() ?: return
        val s = _search.value
        val token = s.continuation ?: return
        if (s.loading) return
        _search.value = s.copy(loading = true)
        searchJob = viewModelScope.launch {
            channelRepository.searchChannel(channel, s.submitted, token).fold(
                onSuccess = { page ->
                    val cur = _search.value
                    val merged = (cur.videos + page.videos).distinctBy { it.id }
                    // No new videos → stop paging (avoids looping on a repeated token).
                    val next = page.continuation.takeIf { merged.size > cur.videos.size }
                    _search.value = cur.copy(loading = false, videos = merged, continuation = next)
                },
                onFailure = { _search.value = _search.value.copy(loading = false, continuation = null) }
            )
        }
    }

    private companion object { const val MAX_ALL = 2000 }
}
