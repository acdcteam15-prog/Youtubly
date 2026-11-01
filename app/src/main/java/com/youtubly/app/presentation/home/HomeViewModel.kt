package com.youtubly.app.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.youtubly.app.core.readable
import com.youtubly.app.domain.model.AccountFeed
import com.youtubly.app.domain.model.AuthState
import com.youtubly.app.domain.model.ExploreCategory
import com.youtubly.app.domain.model.Video
import com.youtubly.app.domain.repository.AccountRepository
import com.youtubly.app.domain.repository.AuthRepository
import com.youtubly.app.domain.usecase.GetTrendingUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** A Home tab. Signed in: your YouTube Home + Subscriptions. Signed out: category charts. */
sealed class Feed(val label: String) {
    data object Home : Feed("Home")
    data object Subscriptions : Feed("Subscriptions")
    data class Explore(val category: ExploreCategory) : Feed(category.label)
}

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data class Success(
        val videos: List<Video>,
        val continuation: String? = null,
        val loadingMore: Boolean = false
    ) : HomeUiState
    data class Error(val message: String) : HomeUiState
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val getExplore: GetTrendingUseCase,
    private val accountRepository: AccountRepository,
    authRepository: AuthRepository,
    videoRepository: com.youtubly.app.domain.repository.VideoRepository,
    private val userPreferences: com.youtubly.app.data.local.datastore.UserPreferences
) : ViewModel() {

    /** Most recent history item that's partly watched — for the "Continue watching" banner. */
    val continueWatching: StateFlow<Video?> = kotlinx.coroutines.flow.combine(
        videoRepository.observeHistory(), videoRepository.observeProgress()
    ) { history, progress ->
        history.firstOrNull { (progress[it.id] ?: 0f) in 0.03f..0.93f }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _dismissedResumeId = MutableStateFlow<String?>(null)
    val dismissedResumeId: StateFlow<String?> = _dismissedResumeId
    fun dismissResume(id: String) { _dismissedResumeId.value = id }

    /** Uploads on the Subscriptions feed you haven't seen yet (0 = no badge). */
    private val _newSubsCount = MutableStateFlow(0)
    val newSubsCount: StateFlow<Int> = _newSubsCount

    private fun refreshSubsBadge() {
        viewModelScope.launch {
            val seen = userPreferences.subsSeenIds.first()
            accountRepository.feed(AccountFeed.SUBSCRIPTIONS).onSuccess { page ->
                // First run: nothing is "new" yet — just take a baseline.
                if (seen.isEmpty()) { markSubsSeen(page.videos); return@onSuccess }
                _newSubsCount.value = page.videos.count { it.id !in seen }
            }
        }
    }

    private suspend fun markSubsSeen(videos: List<Video>) {
        val merged = (videos.map { it.id } + userPreferences.subsSeenIds.first()).distinct().take(300).toSet()
        userPreferences.setSubsSeenIds(merged)
        _newSubsCount.value = 0
    }

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val uiState: StateFlow<HomeUiState> = _uiState

    private val _feed = MutableStateFlow<Feed?>(null)
    val feed: StateFlow<Feed?> = _feed

    private val signedInFlow = authRepository.observeAuthState().map { it is AuthState.SignedIn }.distinctUntilChanged()
    val signedIn: StateFlow<Boolean> = signedInFlow.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val feeds: StateFlow<List<Feed>> = signedInFlow
        .map { si -> if (si) listOf(Feed.Home, Feed.Subscriptions) else ExploreCategory.entries.map { Feed.Explore(it) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var job: Job? = null

    init {
        viewModelScope.launch {
            signedInFlow.collect { si ->
                select(if (si) Feed.Home else Feed.Explore(ExploreCategory.MUSIC), force = true)
                if (si) refreshSubsBadge() else _newSubsCount.value = 0
            }
        }
    }

    fun select(feed: Feed, force: Boolean = false) {
        if (!force && feed == _feed.value) return
        _feed.value = feed
        load()
    }

    fun load() {
        val feed = _feed.value ?: return
        job?.cancel()
        job = viewModelScope.launch {
            _uiState.value = HomeUiState.Loading
            _uiState.value = when (feed) {
                is Feed.Explore -> getExplore(feed.category).fold(
                    onSuccess = { HomeUiState.Success(it) },
                    onFailure = { HomeUiState.Error(it.readable()) }
                )
                else -> accountRepository.feed(feed.toAccountFeed()).fold(
                    onSuccess = {
                        when (feed) {
                            Feed.Subscriptions -> markSubsSeen(it.videos)
                            Feed.Home -> refreshSubsBadge()
                            else -> Unit
                        }
                        HomeUiState.Success(it.videos, it.continuation)
                    },
                    onFailure = { HomeUiState.Error(it.readable()) }
                )
            }
        }
    }

    /** Infinite scroll for Home / Subscriptions. */
    fun loadMore() {
        val feed = _feed.value ?: return
        val s = _uiState.value as? HomeUiState.Success ?: return
        val token = s.continuation ?: return
        if (s.loadingMore || feed is Feed.Explore) return
        _uiState.value = s.copy(loadingMore = true)
        job = viewModelScope.launch {
            accountRepository.feed(feed.toAccountFeed(), token).fold(
                onSuccess = { page ->
                    val known = s.videos.mapTo(HashSet()) { it.id }
                    _uiState.value = s.copy(
                        videos = s.videos + page.videos.filterNot { it.id in known },
                        continuation = page.continuation,
                        loadingMore = false
                    )
                },
                onFailure = { _uiState.value = s.copy(loadingMore = false) }
            )
        }
    }

    private fun Feed.toAccountFeed() = if (this == Feed.Subscriptions) AccountFeed.SUBSCRIPTIONS else AccountFeed.HOME

}
