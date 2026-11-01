package com.youtubly.app.presentation.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.youtubly.app.core.readable
import com.youtubly.app.data.local.datastore.UserPreferences
import com.youtubly.app.domain.model.DownloadItem
import com.youtubly.app.domain.download.sortedFor
import com.youtubly.app.domain.download.sizeBytes
import com.youtubly.app.domain.model.DownloadStorage
import com.youtubly.app.domain.repository.DownloadRepository
import com.youtubly.app.playback.PlayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Live transfer numbers for one running download. */
data class TransferStats(val bytesPerSecond: Long, val etaSeconds: Long?)

/** Totals across everything currently downloading, for the header strip. */
data class TransferSummary(val active: Int, val bytesPerSecond: Long, val etaSeconds: Long?)

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloadRepository: DownloadRepository,
    private val playerController: PlayerController,
    private val userPreferences: UserPreferences,
    private val editorInbox: com.youtubly.app.editor.EditorInbox
) : ViewModel() {

    val sort: StateFlow<com.youtubly.app.domain.download.SavedSort> = userPreferences.savedSort
        .map(com.youtubly.app.domain.download.SavedSort::fromName)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.youtubly.app.domain.download.SavedSort.NEWEST)
    fun setSort(s: com.youtubly.app.domain.download.SavedSort) { viewModelScope.launch { userPreferences.setSavedSort(s.name) } }

    val downloads: StateFlow<List<DownloadItem>?> = kotlinx.coroutines.flow.combine(downloadRepository.observeDownloads(), sort) { list, order ->
        list.sortedFor(order)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Suggestions for the cleaner sheet, recomputed whenever downloads change. */
    val cleanerGroups: StateFlow<List<com.youtubly.app.domain.download.CleanerGroup>> = downloadRepository.observeDownloads()
        .map { com.youtubly.app.domain.download.StorageCleaner.groups(it, System.currentTimeMillis(), playerController.currentVideoId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Deletes the chosen downloads (never the one playing now); reports how much space was freed. */
    fun deleteMany(ids: Set<String>) {
        viewModelScope.launch {
            val playing = playerController.currentVideoId
            val items = downloadRepository.observeDownloads().first().filter { it.id in ids && it.id != playing }
            val freed = items.sumOf { it.sizeBytes() }
            items.forEach { downloadRepository.deleteDownload(it.id) }
            _events.emit(if (items.isEmpty()) "Nothing deleted" else "Deleted ${items.size} · freed ${formatBytes(freed)}")
        }
    }

    private val _storage = MutableStateFlow<DownloadStorage?>(null)
    val storage: StateFlow<DownloadStorage?> = _storage

    val rulesHint: StateFlow<String?> = userPreferences.downloadRules.map { it.queuedHint() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val autoDelete: StateFlow<Boolean> = userPreferences.autoDeleteWatched
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _transfer = MutableStateFlow<Map<String, TransferStats>>(emptyMap())
    /** Download id → speed / ETA, only for downloads that are actively receiving data. */
    val transfer: StateFlow<Map<String, TransferStats>> = _transfer

    val summary: StateFlow<TransferSummary?> = _transfer
        .map { m ->
            if (m.isEmpty()) null
            else TransferSummary(
                active = m.size,
                bytesPerSecond = m.values.sumOf { it.bytesPerSecond },
                // Downloads run in parallel, so the batch is done when the slowest one is.
                etaSeconds = m.values.mapNotNull { it.etaSeconds }.maxOrNull()
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events

    init {
        viewModelScope.launch {
            val n = downloadRepository.autoDeleteWatched(excludeId = playerController.currentVideoId)
            if (n > 0) _events.emit("Auto-deleted $n watched download${if (n == 1) "" else "s"}")
        }
        // Speed / ETA: the worker writes downloadedBytes about once a second, so compare each
        // emission with the previous one and smooth it (exponential moving average) so the
        // numbers don't jump around with every network hiccup.
        viewModelScope.launch {
            val last = HashMap<String, Pair<Long, Long>>()   // id → (bytes, time ms)
            val rate = HashMap<String, Double>()              // id → smoothed bytes/s
            downloadRepository.observeDownloads().collect { list ->
                val now = android.os.SystemClock.elapsedRealtime()
                val running = list.filter { it.status == com.youtubly.app.domain.model.DownloadStatus.RUNNING }
                val out = HashMap<String, TransferStats>()
                for (d in running) {
                    val prev = last[d.id]
                    last[d.id] = d.downloadedBytes to now
                    if (prev == null) continue
                    val dt = (now - prev.second) / 1000.0
                    val db = d.downloadedBytes - prev.first
                    if (dt < 0.25 || db < 0) { rate[d.id]?.let { out[d.id] = stats(it, d) }; continue }
                    val instant = db / dt
                    val smoothed = rate[d.id]?.let { it * 0.7 + instant * 0.3 } ?: instant
                    rate[d.id] = smoothed
                    out[d.id] = stats(smoothed, d)
                }
                // Forget anything no longer running (paused, done, failed, deleted).
                val ids = running.mapTo(HashSet()) { it.id }
                last.keys.retainAll(ids); rate.keys.retainAll(ids)
                _transfer.value = out
            }
        }
        // Re-measure disk usage whenever the set of files / their sizes change.
        viewModelScope.launch {
            downloadRepository.observeDownloads()
                .map { list -> list.map { it.id to (it.status to it.downloadedBytes / (512 * 1024)) } }
                .distinctUntilChanged()
                .collect { _storage.value = downloadRepository.storage() }
        }
    }

    fun delete(id: String) {
        // Local playback's currentVideoId is the download's own id (see playLocal), so this
        // is exactly "is the file we're about to delete the one currently playing".
        if (playerController.currentVideoId == id) playerController.stop()
        viewModelScope.launch { downloadRepository.deleteDownload(id) }
    }

    fun pause(id: String) { viewModelScope.launch { downloadRepository.pause(id) } }

    /** Resume a paused download or retry a failed one (re-resolves fresh stream URLs). */
    fun resume(id: String) {
        viewModelScope.launch {
            downloadRepository.resume(id).onFailure { _events.emit("Couldn't resume: ${it.readable()}") }
        }
    }

    fun deleteWatched() {
        viewModelScope.launch {
            val n = downloadRepository.deleteWatched(excludeId = playerController.currentVideoId)
            _events.emit(if (n == 0) "Nothing watched to delete" else "Deleted $n watched download${if (n == 1) "" else "s"}")
        }
    }

    /** Long-press → Edit: the Edit tab picks this up when it opens. */
    fun sendToEditor(id: String) {
        playerController.takeIf { it.currentVideoId == id }?.player?.pause()
        editorInbox.send(id)
    }

    fun setAutoDelete(v: Boolean) { viewModelScope.launch { userPreferences.setAutoDeleteWatched(v) } }

    private fun stats(bytesPerSec: Double, d: DownloadItem): TransferStats {
        val left = (d.totalBytes - d.downloadedBytes).coerceAtLeast(0)
        val eta = if (d.totalBytes > 0 && bytesPerSec > 1) (left / bytesPerSec).toLong() else null
        return TransferStats(bytesPerSec.toLong(), eta)
    }
}
