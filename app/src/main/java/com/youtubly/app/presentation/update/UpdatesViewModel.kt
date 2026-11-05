package com.youtubly.app.presentation.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.youtubly.app.data.local.datastore.UserPreferences
import com.youtubly.app.update.ReleaseInfo
import com.youtubly.app.update.UpdateRepository
import com.youtubly.app.update.UpdateState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class UpdatesViewModel @Inject constructor(
    private val repository: UpdateRepository,
    private val prefs: UserPreferences
) : ViewModel() {

    val state: StateFlow<UpdateState> = repository.state
    val autoCheck: StateFlow<Boolean> = prefs.autoUpdateCheck.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val token: StateFlow<String> = prefs.githubToken.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val autoDownload: StateFlow<Boolean> = prefs.autoDownloadUpdates.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    fun setAutoDownload(v: Boolean) { viewModelScope.launch { prefs.setAutoDownloadUpdates(v) } }
    val skippedBuild: StateFlow<Int> = prefs.skippedBuild.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val repo: String get() = repository.repo
    val currentVersion: String get() = repository.currentVersion
    val currentBuild: Int get() = repository.currentBuild

    fun check() { viewModelScope.launch { repository.check() } }
    /** Starts the download as background work (notification, continues with the screen off). */
    fun download(info: ReleaseInfo) = repository.startDownload(info)
    fun canInstall(): Boolean = repository.canInstall()
    fun allowInstallIntent() = repository.allowInstallIntent()
    fun installIntent(file: File) = repository.installIntent(file)
    fun install(file: File) = repository.install(file)
    fun restoreReady() = repository.restoreReady()
    fun reset() = repository.reset()

    fun skip(info: ReleaseInfo) { viewModelScope.launch { prefs.setSkippedBuild(info.build); repository.reset() } }
    fun setAuto(v: Boolean) { viewModelScope.launch { prefs.setAutoUpdateCheck(v) } }
    fun setToken(v: String) { viewModelScope.launch { prefs.setGithubToken(v) } }
}

/** Drives the banner on Home: one automatic check per launch (if switched on and not done in the last 6 hours). */
@HiltViewModel
class UpdateBannerViewModel @Inject constructor(
    private val repository: UpdateRepository,
    private val prefs: UserPreferences
) : ViewModel() {

    /** The release to advertise, or null (nothing new / skipped / not checked yet). */
    val available: StateFlow<ReleaseInfo?> = combine(repository.state, prefs.skippedBuild) { s, skipped ->
        val info = (s as? UpdateState.Available)?.info ?: (s as? UpdateState.Ready)?.info
        info?.takeIf { it.build != skipped }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** The new build is already downloaded: one tap installs it. */
    val ready: StateFlow<Boolean> = repository.state.map { it is UpdateState.Ready }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    init {
        viewModelScope.launch {
            repository.announceIfJustUpdated()
            repository.restoreReady()
            val due = System.currentTimeMillis() - prefs.lastUpdateCheck.first() > 6 * 3600_000L
            if (prefs.autoUpdateCheck.first() && due && repository.state.value is UpdateState.Idle) repository.check()
        }
    }
}
