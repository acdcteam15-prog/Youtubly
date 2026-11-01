package com.youtubly.app.presentation.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.youtubly.app.domain.model.DownloadItem
import com.youtubly.app.domain.model.DownloadStatus
import com.youtubly.app.domain.repository.DownloadRepository
import com.youtubly.app.playback.PlayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface LocalPlayerUiState {
    data object Loading : LocalPlayerUiState
    data class Ready(val item: DownloadItem, val hasNext: Boolean, val hasPrevious: Boolean) : LocalPlayerUiState
    data object NotFound : LocalPlayerUiState
}

@HiltViewModel
class LocalPlayerViewModel @Inject constructor(
    private val downloadRepository: DownloadRepository,
    val playerController: PlayerController,
    val sleepTimer: com.youtubly.app.playback.SleepTimer,
    private val userPreferences: com.youtubly.app.data.local.datastore.UserPreferences
) : ViewModel() {

    // ---- playback settings (same controls as the online player, minus stream-only ones) ----
    fun setSpeed(speed: Float) {
        playerController.setSpeed(speed)
        viewModelScope.launch { userPreferences.setPlaybackSpeed(speed) }
    }

    fun setSkipSilence(enabled: Boolean) {
        playerController.setSkipSilence(enabled)
        viewModelScope.launch { userPreferences.setSkipSilence(enabled) }
    }

    fun setBoost(db: Int) = playerController.setBoostDb(db)

    /** Local files restore video in place (no network), so this is a plain toggle. */
    fun toggleListenOnly() = playerController.setVideoDisabled(!playerController.isAudioMode)

    private val _uiState = MutableStateFlow<LocalPlayerUiState>(LocalPlayerUiState.Loading)
    val uiState: StateFlow<LocalPlayerUiState> = _uiState

    private var loadedId: String? = null

    /** Autoplay switch in the controls: advance to the next download when one ends. */
    var autoplayNext = true

    // Playable, in the same order the Downloads tab shows them — that list *is* the queue,
    // rather than a separate concept that could drift out of sync with what's actually there
    // (a download finishing or being deleted mid-playback, for instance).
    private var queue: List<DownloadItem> = emptyList()

    init {
        // Offline watch progress, so "Delete watched" / auto-delete (≥95 %) also count
        // videos watched from the Downloads tab, not just ones watched online.
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(5_000)
                recordProgress()
            }
        }
        viewModelScope.launch {
            playerController.playbackEnded.collect {
                val finished = loadedId
                if (finished != null && playerController.currentVideoId == finished) {
                    downloadRepository.recordLocalProgress(finished, 100)
                }
                // Only advance if we're still the one actually playing — if the user has since
                // navigated to a different download (or away from this screen entirely, though
                // the singleton player keeps running), an end-of-track from some earlier item
                // shouldn't skip the one they're on now.
                if (autoplayNext && playerController.currentVideoId == loadedId) playNext()
                // Never deletes whatever is loaded now (the next item, or the last one if
                // the queue ended and it's still on screen).
                downloadRepository.autoDeleteWatched(excludeId = playerController.currentVideoId)
            }
        }
    }

    private suspend fun recordProgress() {
        val id = loadedId ?: return
        val p = playerController.player
        if (playerController.currentVideoId != id || !p.isPlaying) return
        val dur = p.duration.takeIf { it > 0 } ?: return
        downloadRepository.recordLocalProgress(id, (p.currentPosition * 100 / dur).toInt())
    }

    /** Notification / lock-screen / headset skip buttons go through the downloads queue. */
    private val navigator = object : com.youtubly.app.playback.QueueNavigator {
        private fun index() = queue.indexOfFirst { it.id == loadedId }
        override fun hasNext() = index() in 0 until queue.lastIndex
        override fun hasPrevious() = index() > 0
        override fun next() = playNext()
        override fun previous() = playPrevious()
    }

    override fun onCleared() {
        // Our scope dies with us; don't leave dead skip buttons in the notification.
        val session = playerController.sessionPlayer
        if (session.navigator === navigator) session.navigator = null
        // Best effort; the ViewModel scope is gone, so use a short-lived one.
        val id = loadedId
        val p = playerController.player
        if (id != null && playerController.currentVideoId == id && p.duration > 0) {
            val pct = (p.currentPosition * 100 / p.duration).toInt()
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                downloadRepository.recordLocalProgress(id, pct)
            }
        }
        super.onCleared()
    }

    fun load(downloadId: String) {
        if (loadedId == downloadId && _uiState.value !is LocalPlayerUiState.Loading) return
        loadedId = downloadId
        viewModelScope.launch {
            queue = downloadRepository.observeDownloads().first().filter { it.status == DownloadStatus.COMPLETED }
            openAt(queue.indexOfFirst { it.id == downloadId })
        }
    }

    /** Resume point for [item]: the saved exact position, else (older rows) the coarse watched %. */
    private suspend fun startPositionFor(item: DownloadItem): Long {
        item.resumeAtMs?.let { return it }
        val pct = item.watchedPercent
        if (item.durationMs > 0 || pct !in 5..94) return 0L   // finished / barely started / known-bad
        // Legacy row without a stored duration: derive it from the player once it's ready.
        return 0L
    }

    /** Rows saved before exact positions existed only have a percent: seek by it once the duration is known. */
    private suspend fun legacyResume(item: DownloadItem) {
        val pct = item.watchedPercent
        if (item.durationMs > 0 || pct !in 5..94) return
        val p = playerController.player
        val dur = kotlinx.coroutines.withTimeoutOrNull(3_000) {
            while (p.duration <= 0) kotlinx.coroutines.delay(50)
            p.duration
        } ?: return
        if (playerController.currentVideoId == item.id) p.seekTo(dur * pct / 100)
    }

    fun playNext() {
        val i = queue.indexOfFirst { it.id == loadedId }
        if (i in 0 until queue.lastIndex) openAt(i + 1)
    }

    fun playPrevious() {
        val i = queue.indexOfFirst { it.id == loadedId }
        if (i > 0) openAt(i - 1)
    }

    private fun openAt(index: Int) {
        val item = queue.getOrNull(index)
        val path = item?.filePath
        if (item == null || path == null) {
            _uiState.value = LocalPlayerUiState.NotFound
            return
        }
        loadedId = item.id
        _uiState.value = LocalPlayerUiState.Ready(item, hasNext = index < queue.lastIndex, hasPrevious = index > 0)
        playerController.sessionPlayer.navigator = navigator // setter refreshes the notification
        val p = playerController.player
        // Already loaded and alive (e.g. coming back from the mini player): don't restart it.
        // An errored/stopped player for the same item does get reloaded.
        if (playerController.currentVideoId == item.id && p.mediaItemCount > 0 &&
            p.playerError == null && p.playbackState != androidx.media3.common.Player.STATE_IDLE) return
        val requestedId = item.id
        viewModelScope.launch {
            // The channel decides whether blur applies (excluded channels). Old downloads don't
            // have it stored: resolve once online, briefly, then play regardless.
            val channel = item.uploaderUrl
                ?: kotlinx.coroutines.withTimeoutOrNull(4_000) { downloadRepository.channelUrlFor(item.id) }
            if (loadedId != requestedId) return@launch // user moved on meanwhile
            playerController.playLocal(path, item.title, item.isAudioOnly, item.id, item.thumbnailUrl, channel, startPositionFor(item))
            legacyResume(item)
        }
    }
}
