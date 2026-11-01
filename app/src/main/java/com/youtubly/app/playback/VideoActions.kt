package com.youtubly.app.playback

import com.youtubly.app.data.local.datastore.HiddenRecord
import com.youtubly.app.data.local.datastore.UserPreferences
import com.youtubly.app.domain.model.Video
import com.youtubly.app.domain.repository.AccountRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * YouTube-style long-press actions shared by every video list.
 *
 * "Not interested" / "Don't recommend channel" work everywhere: they're applied locally
 * (persisted, so hidden items never come back in Home, Search, Up next…), and when the
 * video came from the signed-in feed with a feedback token, the token is also sent to
 * YouTube so it trains the real recommendations.
 */
@Singleton
class VideoActions @Inject constructor(
    private val prefs: UserPreferences,
    private val accountRepository: AccountRepository,
    private val getStreamInfo: com.youtubly.app.domain.usecase.GetStreamInfoUseCase,
    private val startDownload: com.youtubly.app.domain.usecase.StartDownloadUseCase
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** For UI work that must outlive the bottom sheet that started it (e.g. toast after a download is queued). */
    fun launchUi(block: suspend () -> Unit) { uiScope.launch { block() } }

    private val _hidden = MutableStateFlow<Set<String>>(emptySet())
    val hiddenVideos: StateFlow<Set<String>> = _hidden
    private val _blocked = MutableStateFlow<Set<String>>(emptySet())
    val blockedChannels: StateFlow<Set<String>> = _blocked

    private val _videoRecords = MutableStateFlow<Map<String, HiddenRecord>>(emptyMap())
    private val _channelRecords = MutableStateFlow<Map<String, HiddenRecord>>(emptyMap())

    /** Hidden videos for the manager screen, newest first. Legacy id-only entries (hidden
     *  before details were stored) show as "Unknown video". */
    val hiddenVideoItems: StateFlow<List<HiddenItem>> =
        combine(_hidden, _videoRecords) { ids, records ->
            ids.map { id ->
                val r = records[id]
                HiddenItem(
                    key = id,
                    title = r?.title?.ifBlank { null } ?: "Unknown video",
                    subtitle = r?.subtitle,
                    thumbnailUrl = r?.thumbnailUrl ?: "https://i.ytimg.com/vi/$id/mqdefault.jpg",
                    url = r?.url ?: "https://www.youtube.com/watch?v=$id",
                    hiddenAt = r?.hiddenAt ?: 0L
                )
            }.sortedByDescending { it.hiddenAt }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Blocked channels, one entry per channel (its URL and "name:" keys grouped together). */
    val blockedChannelItems: StateFlow<List<HiddenItem>> =
        combine(_blocked, _channelRecords) { keys, records ->
            val covered = mutableSetOf<String>()
            val fromRecords = records.mapNotNull { (k, r) ->
                val live = (r.keys.ifEmpty { listOf(k) }).filter { it in keys }
                if (live.isEmpty()) return@mapNotNull null
                covered += live
                HiddenItem(k, r.title.ifBlank { "Unknown channel" }, r.subtitle, r.thumbnailUrl, r.url, r.hiddenAt)
            }
            // Legacy keys with no record: group "name:X" with nothing else we can know about.
            val legacy = (keys - covered).map { k ->
                val isName = k.startsWith("name:")
                HiddenItem(
                    key = k,
                    title = if (isName) k.removePrefix("name:") else k.substringAfterLast('/').ifBlank { "Unknown channel" },
                    subtitle = if (isName) null else k,
                    thumbnailUrl = null,
                    url = k.takeUnless { isName },
                    hiddenAt = 0L
                )
            }
            (fromRecords + legacy).sortedByDescending { it.hiddenAt }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    // Merge (not overwrite) so anything hidden in the first instant after launch isn't lost;
    // persistAll() waits for this so it never saves a half-loaded set.
    private val loaded = scope.launch {
        _hidden.value = prefs.hiddenVideos.first() + _hidden.value
        _blocked.value = prefs.blockedChannels.first() + _blocked.value
        _videoRecords.value = prefs.hiddenVideoRecords.first() + _videoRecords.value
        _channelRecords.value = prefs.blockedChannelRecords.first() + _channelRecords.value
    }

    private fun channelRecordKey(v: Video): String? =
        v.uploaderUrl?.trimEnd('/') ?: v.uploaderName.takeIf { it.isNotBlank() }?.let { "name:$it" }

    private fun channelKeys(v: Video): Set<String> =
        setOfNotNull(v.uploaderUrl?.trimEnd('/'), v.uploaderName.takeIf { it.isNotBlank() }?.let { "name:$it" })

    fun isChannelBlocked(v: Video, blocked: Set<String> = _blocked.value): Boolean =
        channelKeys(v).any { it in blocked }

    fun notInterested(v: Video) {
        _hidden.value = _hidden.value + v.id
        _videoRecords.value = _videoRecords.value + (
            v.id to HiddenRecord(
                title = v.title, subtitle = v.uploaderName.ifBlank { null }, thumbnailUrl = v.thumbnailUrl,
                url = v.url, keys = listOf(v.id), hiddenAt = System.currentTimeMillis()
            )
        )
        persistAll()
        scope.launch {
            v.notInterestedToken?.let { accountRepository.sendFeedback(it) }
        }
    }

    fun dontRecommendChannel(v: Video) {
        val keys = channelKeys(v)
        _blocked.value = _blocked.value + keys
        channelRecordKey(v)?.let { rk ->
            _channelRecords.value = _channelRecords.value + (
                rk to HiddenRecord(
                    title = v.uploaderName, thumbnailUrl = v.uploaderAvatarUrl, url = v.uploaderUrl,
                    keys = keys.toList(), hiddenAt = System.currentTimeMillis()
                )
            )
        }
        persistAll()
        scope.launch {
            v.dontRecommendChannelToken?.let { accountRepository.sendFeedback(it) }
        }
    }

    /** Undo either action (local only — YouTube's own undo isn't exposed without a second token). */
    fun undo(v: Video) {
        _hidden.value = _hidden.value - v.id
        _videoRecords.value = _videoRecords.value - v.id
        _blocked.value = _blocked.value - channelKeys(v)
        channelRecordKey(v)?.let { _channelRecords.value = _channelRecords.value - it }
        persistAll()
    }

    // ---------------- Hidden manager ----------------
    fun restoreVideo(id: String) {
        _hidden.value = _hidden.value - id
        _videoRecords.value = _videoRecords.value - id
        persistAll()
    }

    /** [key] is a HiddenItem.key from [blockedChannelItems] (record key or a legacy filter key). */
    fun restoreChannel(key: String) {
        val record = _channelRecords.value[key]
        val keys = (record?.keys.orEmpty() + key).toSet()
        _blocked.value = _blocked.value - keys
        _channelRecords.value = _channelRecords.value - key
        persistAll()
    }

    fun restoreAllVideos() {
        _hidden.value = emptySet()
        _videoRecords.value = emptyMap()
        persistAll()
    }

    fun restoreAllChannels() {
        _blocked.value = emptySet()
        _channelRecords.value = emptyMap()
        persistAll()
    }

    private val persistLock = kotlinx.coroutines.sync.Mutex()

    /** Writes the *current* state under a lock, so overlapping saves can't land out of
     *  order and leave an older snapshot on disk. */
    private fun persistAll() {
        scope.launch {
            loaded.join()
            persistLock.withLock {
                prefs.setHiddenVideos(_hidden.value)
                prefs.setBlockedChannels(_blocked.value)
                prefs.setHiddenVideoRecords(_videoRecords.value)
                prefs.setBlockedChannelRecords(_channelRecords.value)
            }
        }
    }

    /** Long-press → Download…: resolve streams so the quality picker can list them. */
    suspend fun resolveForDownload(v: Video): Result<com.youtubly.app.domain.model.StreamInfoResult> = getStreamInfo(v.url)

    /** Queues [option] (picked from the quality sheet) — the same path the player uses. */
    suspend fun queueDownload(info: com.youtubly.app.domain.model.StreamInfoResult, option: com.youtubly.app.domain.model.DownloadOption): Result<Unit> =
        runCatching {
            startDownload(info.video.id, info.video.url, info.video.title, info.video.thumbnailUrl, option)
            Unit
        }

    suspend fun saveToWatchLater(v: Video): Result<Unit> = accountRepository.setWatchLater(v.id, true)
}

/** One row in the Hidden videos & channels screen. */
data class HiddenItem(
    val key: String,
    val title: String,
    val subtitle: String?,
    val thumbnailUrl: String?,
    val url: String?,
    val hiddenAt: Long
)
