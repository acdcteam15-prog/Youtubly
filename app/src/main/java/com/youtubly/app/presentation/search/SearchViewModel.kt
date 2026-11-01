package com.youtubly.app.presentation.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.youtubly.app.core.readable
import com.youtubly.app.data.local.dao.SearchHistoryDao
import com.youtubly.app.data.local.entity.SearchHistoryEntity
import com.youtubly.app.domain.model.Video
import com.youtubly.app.domain.repository.AccountRepository
import com.youtubly.app.domain.repository.SearchRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface SearchUiState {
    data object Idle : SearchUiState
    data object Loading : SearchUiState
    data class Success(val query: String, val videos: List<Video>) : SearchUiState
    data class Error(val message: String) : SearchUiState
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchRepository: SearchRepository,
    private val accountRepository: AccountRepository,
    private val searchHistoryDao: SearchHistoryDao,
    private val librarySearch: LibrarySearch
) : ViewModel() {

    private val _uiState = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val uiState: StateFlow<SearchUiState> = _uiState

    /** Persisted locally so it survives process death, unlike the old in-memory-only list. */
    val recent: StateFlow<List<String>> = searchHistoryDao.observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val typed = MutableStateFlow("")

    /** Live YouTube suggestions, 250 ms after the user stops typing. */
    val suggestions: StateFlow<List<String>> = typed
        .map { it.trim() }
        .debounce(250)
        .distinctUntilChanged()
        .flatMapLatest { q ->
            flow {
                if (q.length < 2) emit(emptyList())
                else emit(searchRepository.suggestions(q).getOrDefault(emptyList()).take(10))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Matches from your own subscriptions, playlists, history and downloads. */
    val library: StateFlow<LibraryResults> = typed
        .map { it.trim() }
        .debounce(150)
        .distinctUntilChanged()
        .map { q -> runCatching { librarySearch.search(q) }.getOrDefault(LibraryResults(query = q)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LibraryResults())

    private var job: Job? = null

    fun onQueryChange(q: String) {
        typed.value = q
    }

    fun search(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        if (!com.youtubly.app.core.Incognito.isOn) {
            viewModelScope.launch { searchHistoryDao.upsert(SearchHistoryEntity(q, System.currentTimeMillis())) }
        }
        job?.cancel()
        job = viewModelScope.launch {
            _uiState.value = SearchUiState.Loading
            _uiState.value = searchRepository.search(q).fold(
                onSuccess = { SearchUiState.Success(q, it) },
                onFailure = { SearchUiState.Error(it.readable()) }
            )
        }
    }

    private val _refreshing = MutableStateFlow(false)
    /** Pull-to-refresh on the results: re-runs the current query, keeping the list on screen. */
    val refreshing: StateFlow<Boolean> = _refreshing

    fun refresh() {
        val cur = _uiState.value as? SearchUiState.Success ?: return
        job?.cancel()
        job = viewModelScope.launch {
            _refreshing.value = true
            try {
                searchRepository.search(cur.query).onSuccess { _uiState.value = SearchUiState.Success(cur.query, it) }
            } finally {
                _refreshing.value = false
            }
        }
    }

    fun removeFromHistory(query: String) {
        viewModelScope.launch { searchHistoryDao.delete(query) }
    }

    fun clearHistory() {
        viewModelScope.launch { searchHistoryDao.clear() }
    }

    fun clear() {
        job?.cancel()
        typed.value = ""
        _uiState.value = SearchUiState.Idle
    }

}
