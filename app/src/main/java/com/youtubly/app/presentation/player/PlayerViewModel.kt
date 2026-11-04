package com.youtubly.app.presentation.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import com.youtubly.app.core.NetworkPolicy
import com.youtubly.app.core.readable
import com.youtubly.app.data.local.datastore.UserPreferences
import com.youtubly.app.domain.model.AuthState
import com.youtubly.app.domain.model.CaptionTrack
import com.youtubly.app.domain.model.SponsorSegment
import com.youtubly.app.domain.model.StreamInfoResult
import com.youtubly.app.domain.model.Video
import com.youtubly.app.domain.model.VideoAccountState
import com.youtubly.app.domain.repository.AccountRepository
import com.youtubly.app.domain.repository.AuthRepository
import com.youtubly.app.domain.repository.VideoRepository
import com.youtubly.app.domain.usecase.GetDislikeCountUseCase
import com.youtubly.app.domain.usecase.GetSponsorSegmentsUseCase
import com.youtubly.app.domain.usecase.GetStreamInfoUseCase
import com.youtubly.app.domain.usecase.StartDownloadUseCase
import com.youtubly.app.playback.PlaybackOptions
import com.youtubly.app.playback.PlayerController
import com.youtubly.app.playback.SponsorBlockSkipper
import com.youtubly.app.presentation.components.formatDuration
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface PlayerUiState {
    data object Loading : PlayerUiState
    data class Success(
        val info: StreamInfoResult,
        val dislikeCount: Long?,
        val segments: List<SponsorSegment>,
        val options: PlaybackOptions = PlaybackOptions(),
        val speed: Float = 1f,
        val heights: List<Int> = emptyList(),
        val signedIn: Boolean = false,
        val account: VideoAccountState? = null,
        val inWatchLater: Boolean = false,
        /** Video track off (Listen only / audio-only source). */
        val audioMode: Boolean = false,
        /** Data saver was active when this video loaded (mobile data + setting on). */
        val dataSaver: Boolean = false,
        /** A per-channel speed is stored for this video's channel. */
        val channelSpeedRemembered: Boolean = false,
        /** A speed is stored for this exact video (beats the channel and global speeds). */
        val videoSpeedRemembered: Boolean = false,
        /** Saved per-channel choices for this video's channel (audio only, quality, captions, skip intro). */
        val channelDefaults: com.youtubly.app.domain.playback.ChannelDefaults? = null
    ) : PlayerUiState {
        val audioOnly: Boolean get() = audioMode
    }
    data class Error(val message: String) : PlayerUiState
}

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val getStreamInfo: GetStreamInfoUseCase,
    private val getSponsorSegments: GetSponsorSegmentsUseCase,
    private val getDislikeCount: GetDislikeCountUseCase,
    private val startDownload: StartDownloadUseCase,
    private val videoRepository: VideoRepository,
    private val accountRepository: AccountRepository,
    private val authRepository: AuthRepository,
    private val userPreferences: UserPreferences,
    private val networkPolicy: NetworkPolicy,
    val playerController: PlayerController,
    private val queuePlayer: com.youtubly.app.playback.QueuePlayer,
    val sleepTimer: com.youtubly.app.playback.SleepTimer,
    private val notesRepository: com.youtubly.app.data.repository.NotesRepository,
    private val okHttpClient: okhttp3.OkHttpClient
) : ViewModel() {

    /** The playlist queue being played, if any (shown as a bar with previous / next). */
    val queue: StateFlow<com.youtubly.app.playback.PlayQueue?> = playerController.queue

    /** Autoplay the next queue item when a video ends (the switch on the player). */
    val autoplayNext: StateFlow<Boolean> = userPreferences.autoplayNext
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    fun setAutoplayNext(on: Boolean) {
        viewModelScope.launch {
            userPreferences.setAutoplayNext(on)
            _events.emit(if (on) "Autoplay is on" else "Autoplay is off")
        }
    }
    fun skipQueue(direction: Int) = queuePlayer.skip(direction)
    fun jumpInQueue(index: Int) = queuePlayer.jumpTo(index)
    fun leaveQueue() = playerController.clearQueue()

    private val _uiState = MutableStateFlow<PlayerUiState>(PlayerUiState.Loading)
    val uiState: StateFlow<PlayerUiState> = _uiState

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events

    private val skipper by lazy {
        SponsorBlockSkipper(playerController.player, viewModelScope) { seg ->
            _events.tryEmit("Skipped ${seg.category.replace('_', ' ')}")
        }
    }

    private var loadedUrl: String? = null
    private var saveJob: Job? = null

    init {
        viewModelScope.launch { playerController.errors.collect { _events.emit(it) } }
        // Stream URLs expired while the app was in the background: fetch fresh ones, same position.
        viewModelScope.launch {
            playerController.streamExpired.collect {
                val opts = success()?.options ?: return@collect
                reloadFresh(opts, "Reconnected")
            }
        }
        viewModelScope.launch { playerController.audioMode.collect { a -> updateSuccess { it.copy(audioMode = a) } } }
        viewModelScope.launch { queuePlayer.messages.collect { _events.emit(it) } }
        // Follow a queue advance (made by QueuePlayer) — but only in the screen that was showing
        // the video that just finished, not in older player screens further down the back stack.
        viewModelScope.launch {
            var previous: String? = playerController.nowPlayingId.value
            playerController.nowPlayingId.collect { id ->
                val mine = success()?.info?.video?.id
                val q = playerController.queue.value
                if (id != null && id != mine && mine != null && previous == mine && q?.current?.id == id) {
                    q.current?.let { load(it.url) }
                }
                previous = id
            }
        }
        viewModelScope.launch {
            val skip = userPreferences.skipSilence.first()
            if (playerController.skipSilence.value != skip) playerController.setSkipSilence(skip)
        }
    }

    private fun success(): PlayerUiState.Success? = _uiState.value as? PlayerUiState.Success
    private fun updateSuccess(f: (PlayerUiState.Success) -> PlayerUiState.Success) =
        _uiState.update { if (it is PlayerUiState.Success) f(it) else it }

    /** Start position requested by a link's timestamp, consumed by the next load. */
    private var requestedStartMs: Long? = null

    /** @param startMs from a link timestamp (t=); overrides the saved resume position. */
    fun load(videoUrl: String, force: Boolean = false, startMs: Long? = null) {
        // The sheet's view model lives as long as the app, so "same URL" alone isn't enough:
        // the video must also still be loaded in the player (it isn't after the sheet was closed).
        val stillLoaded = success()?.info?.video?.id?.let { it == playerController.currentVideoId } == true
        if (!force && loadedUrl == videoUrl && _uiState.value is PlayerUiState.Success && stillLoaded) {
            // Same video already open (e.g. a second link with a new timestamp): just seek.
            if (startMs != null) playerController.player.seekTo(startMs)
            return
        }
        requestedStartMs = startMs
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            persistPosition() // save the previous video before switching
            loadedUrl = videoUrl
            // The player already holds this exact video (back from PiP, the notification,
            // a re-opened sheet): rebuild the screen from it instead of waiting on the network.
            val loaded = playerController.loadedInfo
            if (!force && loaded != null && sameVideo(videoUrl, loaded)) {
                skipper.stop()
                onLoaded(loaded)
                return@launch
            }
            _uiState.value = PlayerUiState.Loading
            skipper.stop()
            // Never leave the screen on an endless skeleton: give up with a Retry after a while.
            val result = kotlinx.coroutines.withTimeoutOrNull(LOAD_TIMEOUT_MS) { getStreamInfo(videoUrl) }
            if (result == null) {
                _uiState.value = PlayerUiState.Error("Loading this video is taking too long.\nCheck your connection and try again.")
            } else {
                result.fold(
                    onSuccess = { info -> onLoaded(info) },
                    onFailure = { _uiState.value = PlayerUiState.Error("Couldn't load this video:\n${it.readable()}") }
                )
            }
        }
    }

    /**
     * Called when the app leaves picture-in-picture. If the screen isn't showing the video the
     * player is actually playing (or got stuck loading), resync it from the player — no network.
     */
    fun resyncAfterPip(videoUrl: String) {
        val loaded = playerController.loadedInfo ?: return
        if (success()?.info?.video?.id == loaded.video.id) return
        if (!sameVideo(videoUrl, loaded)) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            loadedUrl = videoUrl
            onLoaded(loaded)
        }
    }

    private fun sameVideo(url: String, info: StreamInfoResult): Boolean =
        url == info.video.url || videoIdOf(url)?.let { it == info.video.id } == true

    private var loadJob: Job? = null

    private suspend fun onLoaded(info: StreamInfoResult) {
        val id = info.video.id
        // Opening a video that isn't the queue's current item (e.g. from Up next) leaves the playlist.
        playerController.queue.value?.let { q -> if (q.current?.id != id) playerController.clearQueue() }
        videoRepository.recordWatched(info.video)

        // Resume where you left off (skip if <10 s in, or basically finished).
        val saved = videoRepository.savedPosition(id)
        val durMs = info.video.duration * 1000
        val linkStart = requestedStartMs?.takeIf { durMs <= 0 || it < durMs }
        requestedStartMs = null
        val resumeAt = linkStart ?: if (saved > 10_000 && (durMs <= 0 || saved < durMs - 15_000)) saved else 0L
        // This channel's saved choices (audio only, quality, captions, skip intro).
        val channelDefaults = com.youtubly.app.domain.playback.ChannelDefaults.lookup(userPreferences.channelDefaults.first(), info.video.uploaderUrl)
        val start = com.youtubly.app.domain.playback.ChannelDefaults.startPosition(linkStart, resumeAt, durMs, channelDefaults?.skipIntroSec ?: 0)
        // Per-channel speed wins over the global one.
        val channelSpeed = com.youtubly.app.domain.playback.ChannelDefaults.lookup(userPreferences.channelSpeeds.first(), info.video.uploaderUrl)
        val videoSpeed = userPreferences.videoSpeeds.first()[id]
        var speed = com.youtubly.app.domain.playback.pickSpeed(videoSpeed, channelSpeed, userPreferences.playbackSpeed.first())
        // Quality cap / data saver are decided once per video from the network right now, so
        // a Wi-Fi ↔ mobile switch mid-video only affects the next one.
        val policy = networkPolicy.forNewVideo()
        var options = PlaybackOptions(
            maxHeight = policy.maxHeight,
            audioOnly = channelDefaults?.audioOnly ?: policy.startAudioOnly,
            qualityHeight = channelDefaults?.qualityHeight,
            caption = channelDefaults?.captionLang?.takeIf { it != "off" }?.let { tag -> info.captions.firstOrNull { it.languageTag == tag } }
        )

        // Re-opened from the in-app mini player: the singleton is already playing this exact
        // video, so just re-attach to it instead of restarting from the saved position.
        val alreadyPlaying = playerController.currentVideoId == id && playerController.player.mediaItemCount > 0
        if (alreadyPlaying) {
            options = playerController.currentOptions
            speed = playerController.player.playbackParameters.speed
            // A link with a timestamp for the video that's already playing: jump there.
            if (linkStart != null) playerController.player.seekTo(linkStart)
        } else {
            runCatching { playerController.play(info, options, start) }
                .onFailure { _events.tryEmit(it.readable()) }
            playerController.setSpeed(speed)
            // This channel's own sound (volume boost / preset), or back to the user's own choice.
            playerController.applyChannelSound(channelDefaults?.volumeBoostDb, channelDefaults?.soundPreset)
            when {
                channelDefaults != null && channelDefaults.skipIntroSec > 0 && start == channelDefaults.skipIntroSec * 1000L && resumeAt < start ->
                    _events.tryEmit("Skipped the intro (${channelDefaults.skipIntroSec} s)")
                start > 0 -> _events.tryEmit("Resumed at ${formatDuration(start / 1000)}")
                videoSpeed != null && videoSpeed != 1f -> _events.tryEmit("${formatSpeed(videoSpeed)} speed for this video")
                channelSpeed != null && channelSpeed != 1f -> _events.tryEmit("${formatSpeed(channelSpeed)} speed for this channel")
            }
        }

        val signedIn = authRepository.observeAuthState().first() is AuthState.SignedIn
        _uiState.value = PlayerUiState.Success(
            info = info, dislikeCount = null, segments = emptyList(),
            options = options, speed = speed, heights = playerController.availableHeights(info), signedIn = signedIn,
            audioMode = playerController.isAudioMode,
            dataSaver = if (alreadyPlaying) networkPolicy.dataSaverActive.value else policy.dataSaver,
            channelSpeedRemembered = channelSpeed != null,
            videoSpeedRemembered = videoSpeed != null,
            channelDefaults = channelDefaults
        )
        startSavingPosition(id)
        startOutroSkip(id, channelDefaults?.skipOutroSec ?: 0)

        viewModelScope.launch {
            val dislikes = async { getDislikeCount(id).getOrNull() }
            val segments = async { getSponsorSegments(id).getOrDefault(emptyList()) }
            val account = async { if (signedIn) accountRepository.videoState(id).getOrNull() else null }
            val segs = segments.await()
            if (userPreferences.autoSkipSponsors.first()) {
                val allowed = userPreferences.sponsorSkipCategories.first()
                skipper.start(segs.filter { it.category in allowed })
            }
            val dislikeCount = dislikes.await()
            val accountState = account.await()
            updateSuccess { it.copy(dislikeCount = dislikeCount, segments = segs, account = accountState) }
        }
    }

    // ---------------- skip outro ----------------
    private var outroJob: Job? = null

    /** Jumps to the end once only the last [seconds] seconds remain (an outro / "subscribe" card). Skipped for short videos. */
    private fun startOutroSkip(videoId: String, seconds: Int) {
        outroJob?.cancel()
        if (seconds <= 0) return
        outroJob = viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(500)
                val p = playerController.player
                if (playerController.currentVideoId != videoId) return@launch
                val dur = p.duration
                if (dur > seconds * 1000L + 30_000L && p.isPlaying && p.currentPosition >= dur - seconds * 1000L) {
                    p.seekTo(dur)          // the end: the next item starts, or playback finishes
                    return@launch
                }
            }
        }
    }

    // ---------------- resume position ----------------
    private fun startSavingPosition(videoId: String) {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            while (isActive) {
                delay(5_000)
                val p = playerController.player
                if (p.isPlaying && playerController.currentVideoId == videoId) {
                    videoRepository.savePosition(videoId, p.currentPosition)
                }
            }
        }
    }

    private suspend fun persistPosition() {
        val s = success() ?: return
        if (playerController.currentVideoId != s.info.video.id) return
        val p = playerController.player
        val durMs = p.duration.takeIf { it > 0 } ?: (s.info.video.duration * 1000)
        // Finished videos keep a full progress bar but restart from 0 next time.
        val pos = if (durMs > 0 && p.currentPosition >= durMs - 15_000) durMs else p.currentPosition
        videoRepository.savePosition(s.info.video.id, pos)
    }

    // ---------------- player options ----------------
    fun setQuality(height: Int?) {
        val s = success() ?: return
        val opts = s.options.copy(qualityHeight = height, audioOnly = false)
        if (playerController.currentOptions.audioOnly) {
            // Only an audio stream is loaded — need fresh URLs for video.
            reloadFresh(opts, "Video restored")
        } else {
            playerController.setVideoDisabled(false)
            reconfigure { opts }
        }
    }

    fun setCaption(track: CaptionTrack?, translateTo: String? = null) =
        reconfigure { it.copy(caption = track, translateTo = if (track == null) null else translateTo) }

    /**
     * Listen only ⇄ Watch video. Turning video off never reloads: the video track is simply
     * deselected so ExoPlayer stops downloading it and audio keeps playing from its buffer.
     * Turning it back on re-enables the track in place — unless the source is audio-only
     * (data-saver start), in which case fresh stream URLs are fetched (old ones expire).
     */
    fun toggleAudioOnly() {
        val s = success() ?: return
        if (!s.audioMode) {
            playerController.setVideoDisabled(true)
            _events.tryEmit("Listen only — keeps playing with the screen off")
            return
        }
        if (playerController.canRestoreVideoInPlace) {
            playerController.setVideoDisabled(false)
            _events.tryEmit("Video restored")
        } else {
            reloadFresh(s.options.copy(audioOnly = false), "Video restored")
        }
    }

    /** Re-resolves stream info (YouTube stream URLs are short-lived) and re-opens this video
     *  at the current position with [opts]. */
    private fun reloadFresh(opts: PlaybackOptions, doneMessage: String) {
        val videoUrl = loadedUrl ?: return
        viewModelScope.launch {
            val pos = playerController.player.currentPosition
            val wasPlaying = playerController.player.playWhenReady
            getStreamInfo(videoUrl).fold(
                onSuccess = { fresh ->
                    playerController.setVideoDisabled(false)
                    runCatching { playerController.play(fresh, opts, pos, newVideo = false) }
                        .onFailure { _events.tryEmit(it.readable()) }
                    playerController.player.playWhenReady = wasPlaying
                    updateSuccess { it.copy(info = fresh, options = opts, heights = playerController.availableHeights(fresh)) }
                    _events.tryEmit(doneMessage)
                },
                onFailure = { _events.tryEmit(it.readable()) }
            )
        }
    }

    private fun reconfigure(change: (PlaybackOptions) -> PlaybackOptions) {
        val s = success() ?: return
        val opts = change(s.options)
        runCatching { playerController.reconfigure(opts) }.onFailure { _events.tryEmit(it.readable()) }
        updateSuccess { it.copy(options = opts) }
    }

    /** While the speed slider is being dragged: change the speed live, but don't write settings on every tick. */
    fun previewSpeed(speed: Float) {
        playerController.setSpeed(speed)
        updateSuccess { it.copy(speed = speed) }
    }

    fun setSpeed(speed: Float) {
        val s = success()
        playerController.setSpeed(speed)
        updateSuccess { it.copy(speed = speed) }
        val channelUrl = s?.info?.video?.uploaderUrl
        val videoId = s?.info?.video?.id
        viewModelScope.launch {
            // A video or channel with a remembered speed keeps its own entry up to date; everything
            // else changes the global default.
            if (s?.videoSpeedRemembered == true && videoId != null) userPreferences.setVideoSpeed(videoId, speed)
            else if (s?.channelSpeedRemembered == true && channelUrl != null) userPreferences.setChannelSpeed(channelUrl, speed)
            else userPreferences.setPlaybackSpeed(speed)
        }
    }

    // ---------------- per-channel defaults ----------------
    /** Remembers today's choices (audio only, quality, captions) for every video of this channel. */
    fun saveChannelDefaults() {
        val s = success() ?: return
        val previous = s.channelDefaults
        // Only the three things this button is about change; everything else saved for the channel is kept.
        val d = (previous ?: com.youtubly.app.domain.playback.ChannelDefaults()).copy(
            audioOnly = s.audioMode || s.options.audioOnly,
            qualityHeight = s.options.qualityHeight,
            captionLang = if (s.info.captions.isEmpty()) previous?.captionLang else (s.options.caption?.languageTag ?: "off")
        )
        storeChannelDefaults(s, d, "Saved for this channel")
    }

    fun setSkipIntro(seconds: Int) {
        val s = success() ?: return
        val base = s.channelDefaults ?: com.youtubly.app.domain.playback.ChannelDefaults()
        storeChannelDefaults(s, base.copy(skipIntroSec = seconds.coerceIn(0, 600)), null)
    }

    /** "The intro ends here": the current position becomes this channel's skip-intro time. */
    fun skipIntroToHere() {
        val sec = (playerController.player.currentPosition / 1000).toInt()
        setSkipIntro(sec)
        _events.tryEmit("Videos of this channel now start at ${formatDuration(sec.toLong())}")
    }

    fun clearChannelDefaults() {
        val s = success() ?: return
        val url = s.info.video.uploaderUrl ?: return
        updateSuccess { it.copy(channelDefaults = null) }
        viewModelScope.launch { userPreferences.setChannelDefaults(url, null) }
        _events.tryEmit("Channel settings cleared")
    }

    private fun storeChannelDefaults(s: PlayerUiState.Success, d: com.youtubly.app.domain.playback.ChannelDefaults, message: String?) {
        val url = s.info.video.uploaderUrl ?: run { _events.tryEmit("This channel can't be identified"); return }
        updateSuccess { it.copy(channelDefaults = d.takeUnless { x -> x.isEmpty }) }
        viewModelScope.launch { userPreferences.setChannelDefaults(url, d) }
        message?.let { _events.tryEmit(it) }
    }

    /** Quick speed button: 1x > 1.25x > 1.5x > 1.75x > 2x > 0.75x > 1x. */
    fun cycleSpeed() {
        val order = listOf(1f, 1.25f, 1.5f, 1.75f, 2f, 0.75f)
        val cur = success()?.speed ?: 1f
        val i = order.indexOfFirst { kotlin.math.abs(it - cur) < 0.01f }
        setSpeed(order[(i + 1).mod(order.size)])
    }

    fun rememberSpeedForVideo() {
        val s = success() ?: return
        updateSuccess { it.copy(videoSpeedRemembered = true) }
        viewModelScope.launch {
            userPreferences.setVideoSpeed(s.info.video.id, s.speed)
            _events.emit("${formatSpeed(s.speed)} remembered for this video")
        }
    }

    fun resetVideoSpeed() {
        val s = success() ?: return
        viewModelScope.launch {
            userPreferences.setVideoSpeed(s.info.video.id, null)
            val channelSpeed = com.youtubly.app.domain.playback.ChannelDefaults.lookup(userPreferences.channelSpeeds.first(), s.info.video.uploaderUrl)
            val back = channelSpeed ?: userPreferences.playbackSpeed.first()
            playerController.setSpeed(back)
            updateSuccess { it.copy(videoSpeedRemembered = false, speed = back) }
            _events.emit("Video speed reset")
        }
    }

    // ---------------- transcript + notes ----------------
    data class TranscriptUi(
        val loading: Boolean = false,
        val cues: List<com.youtubly.app.domain.playback.TranscriptCue> = emptyList(),
        val error: String? = null,
        val trackLabel: String? = null,
        val forVideoId: String? = null
    )

    private val _transcript = MutableStateFlow(TranscriptUi())
    val transcript: StateFlow<TranscriptUi> = _transcript

    /** Downloads the caption file of [track] (optionally auto-translated) and turns it into transcript lines. */
    fun loadTranscript(track: CaptionTrack?, translateTo: String? = null) {
        val s = success() ?: return
        val chosen = track ?: s.options.caption ?: s.info.captions.firstOrNull()
        if (chosen == null) { _transcript.value = TranscriptUi(error = "This video has no captions, so there is no transcript.", forVideoId = s.info.video.id); return }
        val id = s.info.video.id
        val label = chosen.label + (translateTo?.let { " > $it" } ?: "")
        _transcript.value = TranscriptUi(loading = true, trackLabel = label, forVideoId = id)
        viewModelScope.launch {
            val result = withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val url = chosen.url + (translateTo?.let { "&tlang=$it" } ?: "")
                    okHttpClient.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r ->
                        if (!r.isSuccessful) error("HTTP ${r.code}")
                        com.youtubly.app.domain.playback.Transcript.parseVtt(r.body.string())
                    }
                }
            }
            _transcript.value = result.fold(
                onSuccess = { cues ->
                    if (cues.isEmpty()) TranscriptUi(error = "The captions are empty.", trackLabel = label, forVideoId = id)
                    else TranscriptUi(cues = cues, trackLabel = label, forVideoId = id)
                },
                onFailure = { TranscriptUi(error = "Could not load the transcript: ${it.message ?: "network error"}", trackLabel = label, forVideoId = id) }
            )
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val notes: StateFlow<List<com.youtubly.app.data.local.entity.VideoNoteEntity>> = _uiState
        .map { (it as? PlayerUiState.Success)?.info?.video?.id }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) kotlinx.coroutines.flow.flowOf(emptyList()) else notesRepository.notesFor(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addNote(text: String) {
        val s = success() ?: return
        val at = playerController.player.currentPosition
        viewModelScope.launch { notesRepository.add(s.info.video, at, text) }
    }
    fun editNote(id: Long, text: String) { viewModelScope.launch { notesRepository.edit(id, text) } }
    fun deleteNote(id: Long) { viewModelScope.launch { notesRepository.delete(id) } }

    fun seekToMs(ms: Long) { playerController.player.seekTo(ms.coerceAtLeast(0)) }
    fun currentPositionMs(): Long = playerController.player.currentPosition

    fun rememberSpeedForChannel() {
        val s = success() ?: return
        val url = s.info.video.uploaderUrl ?: run { _events.tryEmit("Couldn't identify this channel"); return }
        updateSuccess { it.copy(channelSpeedRemembered = true) }
        viewModelScope.launch {
            userPreferences.setChannelSpeed(url, s.speed)
            _events.emit("${formatSpeed(s.speed)} remembered for ${s.info.video.uploaderName}")
        }
    }

    fun resetChannelSpeed() {
        val s = success() ?: return
        val url = s.info.video.uploaderUrl ?: return
        viewModelScope.launch {
            userPreferences.setChannelSpeed(url, null)
            val global = userPreferences.playbackSpeed.first()
            playerController.setSpeed(global)
            updateSuccess { it.copy(channelSpeedRemembered = false, speed = global) }
            _events.emit("Channel speed reset")
        }
    }

    // ---------------- audio effects ----------------
    fun setSkipSilence(enabled: Boolean) {
        playerController.setSkipSilence(enabled)
        viewModelScope.launch { userPreferences.setSkipSilence(enabled) }
    }

    fun setBoost(db: Int) = playerController.setBoostDb(db)

    // ---------------- A–B loop ----------------
    fun setLoopA() {
        playerController.setLoopA()
        _events.tryEmit("A set at ${formatDuration(playerController.player.currentPosition / 1000)}")
    }

    fun setLoopB() {
        if (playerController.setLoopB()) _events.tryEmit("Looping A–B")
        else _events.tryEmit("B must be after A")
    }

    fun setLoopEnabled(enabled: Boolean) = playerController.setLoopEnabled(enabled)
    fun clearLoop() = playerController.clearLoop()

    fun seekTo(seconds: Int) = playerController.player.seekTo(seconds * 1000L)


    // ---------------- account actions (optimistic) ----------------
    fun toggleLike() {
        val s = success() ?: return
        if (!s.signedIn) { _events.tryEmit("Sign in on the You tab to like videos"); return }
        val acc = s.account ?: VideoAccountState(false, null, null)
        val liked = !acc.liked
        updateSuccess {
            it.copy(
                account = acc.copy(liked = liked),
                info = it.info.copy(likeCount = it.info.likeCount?.let { c -> c + if (liked) 1 else -1 })
            )
        }
        viewModelScope.launch {
            accountRepository.setLiked(s.info.video.id, liked).onFailure {
                updateSuccess { cur -> cur.copy(account = acc, info = s.info) }
                _events.emit("Couldn't update like: ${it.readable()}")
            }
        }
    }

    fun toggleSubscribe() {
        val s = success() ?: return
        if (!s.signedIn) { _events.tryEmit("Sign in on the You tab to subscribe"); return }
        val acc = s.account ?: VideoAccountState(false, null, null)
        val channelId = acc.channelId
            ?: s.info.video.uploaderUrl?.substringAfter("/channel/", "")?.takeIf { it.startsWith("UC") }
            ?: run { _events.tryEmit("Couldn't find this channel's id"); return }
        val sub = acc.subscribed != true
        updateSuccess { it.copy(account = acc.copy(subscribed = sub, channelId = channelId)) }
        viewModelScope.launch {
            accountRepository.setSubscribed(channelId, sub).fold(
                onSuccess = { _events.emit(if (sub) "Subscribed to ${s.info.video.uploaderName}" else "Unsubscribed") },
                onFailure = {
                    updateSuccess { cur -> cur.copy(account = acc) }
                    _events.emit("Couldn't update subscription: ${it.readable()}")
                }
            )
        }
    }

    fun toggleWatchLater() {
        val s = success() ?: return
        if (!s.signedIn) { _events.tryEmit("Sign in on the You tab to use Watch later"); return }
        val save = !s.inWatchLater
        updateSuccess { it.copy(inWatchLater = save) }
        viewModelScope.launch {
            accountRepository.setWatchLater(s.info.video.id, save).fold(
                onSuccess = { _events.emit(if (save) "Saved to Watch later" else "Removed from Watch later") },
                onFailure = {
                    updateSuccess { cur -> cur.copy(inWatchLater = !save) }
                    _events.emit("Couldn't update Watch later: ${it.readable()}")
                }
            )
        }
    }

    // ---------------- downloads ----------------
    fun download(option: com.youtubly.app.domain.model.DownloadOption) {
        val s = success() ?: return
        viewModelScope.launch {
            runCatching { startDownload(s.info.video.id, s.info.video.url, s.info.video.title, s.info.video.thumbnailUrl, option, s.info.video.uploaderUrl) }
                .onSuccess { _events.emit("Downloading ${option.label}") }
                .onFailure { _events.emit("Download failed: ${it.readable()}") }
        }
    }

    override fun onCleared() {
        skipper.stop()
        saveJob?.cancel()
        val s = success()
        if (s != null && playerController.currentVideoId == s.info.video.id) {
            // Best-effort final save; the singleton player keeps playing in the background.
            val pos = playerController.player.currentPosition
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                withContext(NonCancellable) { videoRepository.savePosition(s.info.video.id, pos) }
            }
        }
        super.onCleared()
    }

    private companion object {
        const val LOAD_TIMEOUT_MS = 30_000L

        fun videoIdOf(url: String): String? {
            val u = android.net.Uri.parse(url)
            u.getQueryParameter("v")?.let { return it }
            val seg = u.path?.trim('/')?.split('/').orEmpty()
            return when {
                u.host?.contains("youtu.be") == true -> seg.firstOrNull()
                seg.size >= 2 && seg[0] in setOf("shorts", "live", "embed") -> seg[1]
                else -> null
            }
        }
    }
}


internal fun formatSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}×" else "${speed}×"
