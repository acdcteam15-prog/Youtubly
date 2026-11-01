package com.youtubly.app.presentation.subscriptions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.youtubly.app.core.readable
import com.youtubly.app.data.local.dao.SubscriptionCacheDao
import com.youtubly.app.data.local.datastore.UserPreferences
import com.youtubly.app.data.local.entity.SubscriptionEntity
import com.youtubly.app.domain.model.AuthState
import com.youtubly.app.domain.model.ChannelWatchStat
import com.youtubly.app.domain.model.SubscribedChannel
import com.youtubly.app.domain.repository.AccountRepository
import com.youtubly.app.domain.repository.AuthRepository
import com.youtubly.app.domain.repository.VideoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ChannelSort(val label: String) { AZ("A–Z"), MOST_WATCHED("Most watched"), RECENT("Recently watched"), NOT_WATCHED("Not watched yet") }

/** One row of the Subscriptions screen: the channel plus what you've watched of it here. */
data class ChannelRow(val channel: SubscribedChannel, val stat: ChannelWatchStat?, val pinned: Boolean)

data class SubscriptionsUi(
    val signedIn: Boolean = false,
    val loading: Boolean = true,
    val error: String? = null,
    val total: Int = 0,
    val pinned: List<ChannelRow> = emptyList(),
    val others: List<ChannelRow> = emptyList()
)

@HiltViewModel
class SubscriptionsViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    authRepository: AuthRepository,
    videoRepository: VideoRepository,
    private val userPreferences: UserPreferences,
    private val subscriptionCache: SubscriptionCacheDao
) : ViewModel() {

    private val channels = MutableStateFlow<List<SubscribedChannel>?>(null)
    private val error = MutableStateFlow<String?>(null)
    private val signedIn = authRepository.observeAuthState().map { it is AuthState.SignedIn }.distinctUntilChanged()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query
    private val _sort = MutableStateFlow(ChannelSort.AZ)
    val sort: StateFlow<ChannelSort> = _sort

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events

    private val filters = combine(_query, _sort) { q, s -> q.trim() to s }

    val ui: StateFlow<SubscriptionsUi> = combine(
        signedIn,
        combine(channels, error) { c, e -> c to e },
        videoRepository.observeChannelStats(),
        userPreferences.pinnedChannels,
        filters
    ) { si, (list, err), stats, pins, (q, sort) ->
        if (!si) return@combine SubscriptionsUi(signedIn = false, loading = false)
        if (list == null) return@combine SubscriptionsUi(signedIn = true, loading = err == null, error = err)
        val rows = list
            .filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }
            .map { ChannelRow(it, stats[it.name.trim().lowercase()], it.channelId in pins) }
            .let { sortRows(it, sort) }
        SubscriptionsUi(
            signedIn = true,
            loading = false,
            error = err,
            total = list.size,
            pinned = rows.filter { it.pinned },
            others = rows.filterNot { it.pinned }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SubscriptionsUi())

    init {
        viewModelScope.launch { signedIn.collect { if (it) load() else channels.value = emptyList() } }
    }

    fun load() {
        viewModelScope.launch {
            error.value = null
            accountRepository.subscribedChannels().fold(
                onSuccess = { list ->
                    channels.value = list
                    val now = System.currentTimeMillis()
                    runCatching { subscriptionCache.replaceAll(list.map { SubscriptionEntity(it.channelId, it.name, now) }) }
                },
                onFailure = { error.value = it.readable() }
            )
        }
    }

    fun setQuery(q: String) { _query.value = q }
    fun setSort(s: ChannelSort) { _sort.value = s }
    fun togglePin(row: ChannelRow) { viewModelScope.launch { userPreferences.setChannelPinned(row.channel.channelId, !row.pinned) } }

    /** Removes the row immediately; puts it back if YouTube refuses. */
    fun unsubscribe(channel: SubscribedChannel) {
        val before = channels.value ?: return
        channels.value = before.filterNot { it.channelId == channel.channelId }
        viewModelScope.launch {
            accountRepository.setSubscribed(channel.channelId, false).fold(
                onSuccess = {
                    runCatching { subscriptionCache.delete(channel.channelId) }
                    userPreferences.setChannelPinned(channel.channelId, false)
                    _events.emit("Unsubscribed from ${channel.name}")
                },
                onFailure = {
                    channels.value = before
                    _events.emit("Couldn't unsubscribe: ${it.readable()}")
                }
            )
        }
    }

    companion object {
        fun sortRows(rows: List<ChannelRow>, sort: ChannelSort): List<ChannelRow> {
            val byName = compareBy<ChannelRow> { it.channel.name.lowercase() }
            return when (sort) {
                ChannelSort.AZ -> rows.sortedWith(byName)
                ChannelSort.MOST_WATCHED -> rows.sortedWith(compareByDescending<ChannelRow> { it.stat?.watchedMs ?: 0L }.then(byName))
                ChannelSort.RECENT -> rows.sortedWith(compareByDescending<ChannelRow> { it.stat?.lastWatchedAt ?: 0L }.then(byName))
                ChannelSort.NOT_WATCHED -> rows.filter { it.stat == null }.sortedWith(byName)
            }
        }
    }
}
