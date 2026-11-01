package com.youtubly.app.data.repository

import com.youtubly.app.data.local.dao.DownloadDao
import com.youtubly.app.data.local.dao.WatchHistoryDao
import com.youtubly.app.domain.channel.PlaylistAutoFinish
import com.youtubly.app.domain.channel.PlaylistProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs for the life of the app: when a video of an "auto-remove finished" playlist has been watched (90 % online,
 * or offline from Downloads), it moves out of that playlist; when the last one leaves, the playlist goes on the
 * Finished shelf. Watching the database (not the player) means every way of finishing a video counts, and a
 * change made while the app was closed is picked up at the next launch.
 */
@Singleton
class PlaylistAutoFinisher @Inject constructor(
    private val playlists: PlaylistRepository,
    private val watchHistoryDao: WatchHistoryDao,
    private val downloadDao: DownloadDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    @OptIn(FlowPreview::class)
    fun start() {
        if (started) return
        started = true
        scope.launch {
            val watchedOnline = watchHistoryDao.observeProgress().map { rows ->
                rows.filter { it.durationSec > 0 && PlaylistProgress.isWatched(it.lastPositionMs / 1000f / it.durationSec) }
                    .map { it.videoId }
            }
            val watched = combine(watchedOnline, downloadDao.observeWatchedVideoIds()) { a, b -> (a + b).toSet() }
                .distinctUntilChanged()
            combine(playlists.autoRemoveCandidates(), watched) { candidates, w -> PlaylistAutoFinish.due(candidates, w) }
                .filter { it.isNotEmpty() }
                // Positions are saved every few seconds while playing; don't churn the database for each one.
                .debounce(1_500)
                .collect { due -> due.forEach { (playlistId, ids) -> runCatching { playlists.finish(playlistId, ids) } } }
        }
    }
}
