package com.youtubly.app.presentation.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.youtubly.app.data.local.datastore.UserPreferences
import com.youtubly.app.domain.model.HistoryEntry
import com.youtubly.app.domain.model.HistoryRestore
import com.youtubly.app.domain.repository.VideoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

/** A channel that appears in history, for "Remove a channel". */
data class HistoryChannel(val name: String, val count: Int)

/** Result of a bulk cleanup, shown in a snackbar with Undo. */
data class CleanupResult(val message: String, val restore: HistoryRestore)

/** A day heading and the entries watched that day, newest first. */
data class HistoryDay(val label: String, val entries: List<HistoryEntry>)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val videoRepository: VideoRepository,
    private val userPreferences: UserPreferences
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    /** null = still loading. */
    val days: StateFlow<List<HistoryDay>?> = _query
        .debounce(200)
        .flatMapLatest { q -> videoRepository.observeHistoryEntries(q) }
        .map { groupByDay(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val paused: StateFlow<Boolean> = userPreferences.pauseHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setQuery(q: String) { _query.value = q }
    fun remove(videoId: String) { viewModelScope.launch { videoRepository.removeFromHistory(videoId) } }
    fun clearAll() { viewModelScope.launch { videoRepository.clearHistory() } }
    fun setPaused(v: Boolean) { viewModelScope.launch { userPreferences.setPauseHistory(v) } }

    // ---------------- Cleanup tools ----------------

    /** Whole history regardless of the search box — cleanup always works on everything. */
    val all: StateFlow<List<HistoryEntry>> = videoRepository.observeHistoryEntries("")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Channels in history, most-watched first. */
    val channels: StateFlow<List<HistoryChannel>> = all
        .map { list ->
            list.groupingBy { it.video.uploaderName }.eachCount()
                .map { (name, n) -> HistoryChannel(name, n) }
                .sortedWith(compareByDescending<HistoryChannel> { it.count }.thenBy { it.name.lowercase() })
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selection = MutableStateFlow<Set<String>>(emptySet())
    /** Video ids picked in multi-select mode; empty = not selecting. */
    val selection: StateFlow<Set<String>> = _selection

    private val _selecting = MutableStateFlow(false)
    val selecting: StateFlow<Boolean> = _selecting

    private val _results = MutableSharedFlow<CleanupResult>(extraBufferCapacity = 4)
    val results: SharedFlow<CleanupResult> = _results

    fun startSelection(firstId: String? = null) {
        _selecting.value = true
        _selection.value = setOfNotNull(firstId)
    }
    fun toggleSelected(id: String) {
        _selection.value = _selection.value.let { if (id in it) it - id else it + id }
    }
    fun selectAll(ids: Collection<String>) { _selection.value = _selection.value + ids }
    fun endSelection() { _selecting.value = false; _selection.value = emptySet() }

    fun removeSelected() {
        val ids = _selection.value
        endSelection()
        bulkRemove(ids, what = "")
    }

    /** How many entries each cleanup would remove — shown before the user confirms. */
    fun countOlderThan(days: Int, now: Long = System.currentTimeMillis()): Int =
        all.value.count { it.watchedAt < now - days * DAY_MS }
    fun countWatched(): Int = all.value.count { it.progress >= WATCHED }

    fun removeOlderThan(days: Int) {
        val cutoff = System.currentTimeMillis() - days * DAY_MS
        bulkRemove(all.value.filter { it.watchedAt < cutoff }.map { it.video.id }, what = " older than ${days} days")
    }

    fun removeWatched() =
        bulkRemove(all.value.filter { it.progress >= WATCHED }.map { it.video.id }, what = " you finished")

    fun removeChannel(name: String) =
        bulkRemove(all.value.filter { it.video.uploaderName == name }.map { it.video.id }, what = " from $name")

    fun undo(restore: HistoryRestore) { viewModelScope.launch { videoRepository.restoreHistory(restore) } }

    private fun bulkRemove(ids: Collection<String>, what: String) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val restore = videoRepository.removeFromHistory(ids)
            val n = restore.count
            if (n > 0) _results.emit(CleanupResult("Removed $n video${if (n == 1) "" else "s"}$what", restore))
        }
    }

    companion object {
        private const val DAY_MS = 86_400_000L
        /** Same "finished" threshold the Downloads screen uses. */
        const val WATCHED = 0.95f
        private val DATE = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault())

        fun groupByDay(
            entries: List<HistoryEntry>,
            zone: ZoneId = ZoneId.systemDefault(),
            today: LocalDate = LocalDate.now(zone)
        ): List<HistoryDay> =
            entries
                .groupBy { Instant.ofEpochMilli(it.watchedAt).atZone(zone).toLocalDate() }
                .toSortedMap(compareByDescending { it })
                .map { (day, list) -> HistoryDay(dayLabel(day, today), list.sortedByDescending { it.watchedAt }) }

        fun dayLabel(day: LocalDate, today: LocalDate): String = when (day) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> DATE.format(day)
        }
    }
}
