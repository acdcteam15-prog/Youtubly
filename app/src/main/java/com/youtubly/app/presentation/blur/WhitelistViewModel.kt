package com.youtubly.app.presentation.blur

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.youtubly.app.blur.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WhitelistViewModel @Inject constructor(
    engine: BlurEngine,
    subs: SubscriptionsProvider,
) : ViewModel() {
    private val store = engine.store
    val query = MutableStateFlow("")

    val excluded: StateFlow<List<ChannelRef>> = combine(store.whitelistChannels, query) { list, q ->
        if (q.isBlank()) list else list.filter { it.name.contains(q, true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Subscriptions not yet excluded — candidates for quick add. */
    val suggestions: StateFlow<List<ChannelRef>> = combine(subs.subscriptions, store.whitelistChannels, query) { s, wl, q ->
        val ids = wl.map { it.id }.toSet()
        s.filter { it.id !in ids && (q.isBlank() || it.name.contains(q, true)) }.sortedBy { it.name.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalExcluded = store.whitelistChannels.map { it.size }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun add(ch: ChannelRef) = viewModelScope.launch { store.addToWhitelist(ch) }
    fun remove(ch: ChannelRef) = viewModelScope.launch { store.removeFromWhitelist(ch.id) }
    fun clearAll() = viewModelScope.launch { store.clearWhitelist() }
    fun restore(list: List<ChannelRef>) = viewModelScope.launch { list.forEach { store.addToWhitelist(it) } }
}
