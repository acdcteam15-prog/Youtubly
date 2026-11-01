package com.youtubly.app.presentation.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.youtubly.app.core.readable
import com.youtubly.app.data.local.datastore.UserPreferences
import com.youtubly.app.domain.model.AccountFeed
import com.youtubly.app.domain.model.AuthState
import com.youtubly.app.domain.model.SubscribedChannel
import com.youtubly.app.domain.model.UserProfile
import com.youtubly.app.domain.model.Video
import com.youtubly.app.domain.repository.AccountRepository
import com.youtubly.app.domain.repository.AuthRepository
import com.youtubly.app.domain.repository.VideoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AccountData(
    val loading: Boolean = false,
    val profile: UserProfile? = null,
    val channels: List<SubscribedChannel> = emptyList(),
    val watchLater: List<Video> = emptyList(),
    val liked: List<Video> = emptyList(),
    val error: String? = null
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val accountRepository: AccountRepository,
    videoRepository: VideoRepository,
    private val userPreferences: UserPreferences,
    private val subscriptionCache: com.youtubly.app.data.local.dao.SubscriptionCacheDao,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context
) : ViewModel() {

    val authState: StateFlow<AuthState> = authRepository.observeAuthState()
        .stateIn(viewModelScope, SharingStarted.Eagerly, AuthState.SignedOut)

    val autoSkipSponsors: StateFlow<Boolean> = userPreferences.autoSkipSponsors
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val deArrow: StateFlow<Boolean> = userPreferences.deArrow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val autoPip: StateFlow<Boolean> = userPreferences.autoPip
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val themeMode: StateFlow<String> = userPreferences.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "SYSTEM")
    val accent: StateFlow<Int> = userPreferences.accent
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    fun setThemeMode(v: String) { viewModelScope.launch { userPreferences.setThemeMode(v) } }
    fun setAccent(v: Int) { viewModelScope.launch { userPreferences.setAccent(v); refreshWidget() } }
    val tintedSurfaces: StateFlow<Boolean> = userPreferences.tintedSurfaces
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    fun setTintedSurfaces(v: Boolean) { viewModelScope.launch { userPreferences.setTintedSurfaces(v) } }

    // Phase 5: Material You + double-tap seek step
    val dynamicColor: StateFlow<Boolean> = userPreferences.dynamicColor
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    fun setDynamicColor(v: Boolean) { viewModelScope.launch { userPreferences.setDynamicColor(v); refreshWidget() } }
    val seekStep: StateFlow<Int> = userPreferences.seekStepSeconds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 10)
    fun setSeekStep(v: Int) { viewModelScope.launch { userPreferences.setSeekStepSeconds(v) } }
    val swipe: StateFlow<com.youtubly.app.presentation.components.SwipeSettings> = userPreferences.swipeActions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.youtubly.app.presentation.components.SwipeSettings())
    fun setSwipeRight(v: Boolean) { viewModelScope.launch { userPreferences.setSwipeRight(v) } }
    fun setSwipeLeft(v: Boolean) { viewModelScope.launch { userPreferences.setSwipeLeft(v) } }
    fun setSwipeDistance(v: Int) { viewModelScope.launch { userPreferences.setSwipeDistance(v) } }
    val rotateToFullscreen: StateFlow<Boolean> = userPreferences.rotateToFullscreen
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    fun setRotateToFullscreen(v: Boolean) { viewModelScope.launch { userPreferences.setRotateToFullscreen(v) } }

    /** The home-screen widget draws with the accent, so re-render it after a colour change. */
    private suspend fun refreshWidget() { com.youtubly.app.widget.YoutublyWidget.refresh(appContext) }

    val downloadRules: StateFlow<com.youtubly.app.domain.download.DownloadRules> = userPreferences.downloadRules
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.youtubly.app.domain.download.DownloadRules())
    fun setDownloadRules(r: com.youtubly.app.domain.download.DownloadRules) { viewModelScope.launch { userPreferences.setDownloadRules(r) } }

    val showShortsTab: StateFlow<Boolean> = userPreferences.showShortsTab
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    fun setShowShortsTab(v: Boolean) { viewModelScope.launch { userPreferences.setShowShortsTab(v) } }

    val backgroundAudio: StateFlow<Boolean> = userPreferences.backgroundAudio
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val wifiQuality: StateFlow<Int> = userPreferences.wifiQuality
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val mobileQuality: StateFlow<Int> = userPreferences.mobileQuality
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 480)
    val dataSaver: StateFlow<Boolean> = userPreferences.dataSaver
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val dataSaverAudioOnly: StateFlow<Boolean> = userPreferences.dataSaverAudioOnly
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val history: StateFlow<List<Video>> = videoRepository.observeHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _account = MutableStateFlow(AccountData())
    val account: StateFlow<AccountData> = _account

    init {
        viewModelScope.launch {
            authRepository.observeAuthState().map { it is AuthState.SignedIn }.distinctUntilChanged().collect { signedIn ->
                if (signedIn) loadAccount() else {
                    _account.value = AccountData()
                    // Signed out: don't keep offering the old account's channels in Search.
                    runCatching { subscriptionCache.clear() }
                }
            }
        }
    }

    fun loadAccount() {
        viewModelScope.launch {
            _account.value = _account.value.copy(loading = true, error = null)
            val profileDeferred = async { accountRepository.profile() }
            val channelsDeferred = async { accountRepository.subscribedChannels() }
            val wlDeferred = async { accountRepository.feed(AccountFeed.WATCH_LATER) }
            val likedDeferred = async { accountRepository.feed(AccountFeed.LIKED) }
            val profile = profileDeferred.await()
            val channels = channelsDeferred.await()
            val wl = wlDeferred.await()
            val liked = likedDeferred.await()
            // Keep the local channel list fresh so Search can match your subscriptions offline.
            channels.getOrNull()?.let { list ->
                val now = System.currentTimeMillis()
                runCatching {
                    subscriptionCache.replaceAll(list.map { com.youtubly.app.data.local.entity.SubscriptionEntity(it.channelId, it.name, now) })
                }
            }
            _account.value = AccountData(
                loading = false,
                profile = profile.getOrNull(),
                channels = channels.getOrDefault(emptyList()),
                watchLater = wl.getOrNull()?.videos.orEmpty(),
                liked = liked.getOrNull()?.videos.orEmpty(),
                error = listOf(profile, channels, wl, liked).firstNotNullOfOrNull { it.exceptionOrNull() }?.readable()
            )
        }
    }

    fun signOut() { viewModelScope.launch { authRepository.signOut() } }
    fun setAutoSkip(v: Boolean) { viewModelScope.launch { userPreferences.setAutoSkipSponsors(v) } }
    fun setDeArrow(v: Boolean) { viewModelScope.launch { userPreferences.setDeArrow(v) } }
    fun setAutoPip(v: Boolean) { viewModelScope.launch { userPreferences.setAutoPip(v) } }
    val autoplayNext: StateFlow<Boolean> = userPreferences.autoplayNext
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    fun setAutoplayNext(v: Boolean) { viewModelScope.launch { userPreferences.setAutoplayNext(v) } }

    // ---- Bedtime mode ----
    val bedtimeOn: StateFlow<Boolean> = userPreferences.bedtimeOn.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val bedtimeStart: StateFlow<Int> = userPreferences.bedtimeStartMin.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 22 * 60)
    val bedtimeEnd: StateFlow<Int> = userPreferences.bedtimeEndMin.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 7 * 60)
    val bedtimeVolume: StateFlow<Int> = userPreferences.bedtimeVolumePct.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 60)
    fun setBedtimeOn(v: Boolean) { viewModelScope.launch { userPreferences.setBedtimeOn(v) } }
    fun setBedtimeStart(min: Int) { viewModelScope.launch { userPreferences.setBedtimeStartMin(min) } }
    fun setBedtimeEnd(min: Int) { viewModelScope.launch { userPreferences.setBedtimeEndMin(min) } }
    fun setBedtimeVolume(pct: Int) { viewModelScope.launch { userPreferences.setBedtimeVolumePct(pct) } }
    fun setBackgroundAudio(v: Boolean) { viewModelScope.launch { userPreferences.setBackgroundAudio(v) } }
    fun setWifiQuality(v: Int) { viewModelScope.launch { userPreferences.setWifiQuality(v) } }
    fun setMobileQuality(v: Int) { viewModelScope.launch { userPreferences.setMobileQuality(v) } }
    fun setDataSaver(v: Boolean) { viewModelScope.launch { userPreferences.setDataSaver(v) } }
    fun setDataSaverAudioOnly(v: Boolean) { viewModelScope.launch { userPreferences.setDataSaverAudioOnly(v) } }
}
