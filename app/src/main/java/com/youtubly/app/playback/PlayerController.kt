package com.youtubly.app.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.common.MimeTypes
import com.youtubly.app.domain.model.CaptionTrack
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.youtubly.app.domain.model.StreamInfoResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the single ExoPlayer shared by the UI and PlaybackService.
 *
 * YouTube only serves a stream URL to the same kind of client that requested it
 * (Android app, iOS app, visionOS, or web). NewPipe asks YouTube as several of those
 * clients, so each request here gets the matching User-Agent — otherwise googlevideo
 * answers 403 and nothing plays.
 */
/** User-chosen playback options; everything null = automatic. */
data class PlaybackOptions(
    val qualityHeight: Int? = null,
    val caption: CaptionTrack? = null,
    val translateTo: String? = null,
    /** Source-level audio-only: only an audio stream is loaded (cheapest; used by data saver). */
    val audioOnly: Boolean = false,
    /** Network quality cap for Auto quality (null = no cap). Ignored when [qualityHeight] is set. */
    val maxHeight: Int? = null
)

/**
 * Steps in the volume-boost picker. Was capped at +10 dB (about 3x), not enough for quiet recordings.
 * The platform LoudnessEnhancer applies gain through a limiter, so the higher steps get loud
 * instead of just clipping.
 */
object VolumeBoost {
    val LEVELS_DB = listOf(0, 6, 10, 15, 20, 30)
    const val MAX_DB = 30
}

/** A–B loop state. Positions in ms; the loop only runs when [enabled] and both points exist. */
/**
 * A small play queue (Phase 6: local playlists; later "Up next"). [index] points at the item
 * playing now. Lives on the singleton so it survives leaving and re-entering the player.
 */
data class PlayQueue(val title: String, val items: List<com.youtubly.app.domain.model.Video>, val index: Int) {
    val current: com.youtubly.app.domain.model.Video? get() = items.getOrNull(index)
    val hasNext: Boolean get() = index < items.lastIndex
    val hasPrevious: Boolean get() = index > 0
}

data class AbLoop(val a: Long? = null, val b: Long? = null, val enabled: Boolean = false) {
    val isActive: Boolean get() = enabled && a != null && b != null && b > a
}

@OptIn(UnstableApi::class)
@Singleton
class PlayerController @Inject constructor(
    @ApplicationContext private val context: Context,
    /** Live face blurring through ExoPlayer video effects (media3-effect). */
    private val playbackBlur: com.youtubly.app.blur.playback.PlaybackBlur,
    /** Live voice-only audio (Spleeter on-device) through a custom audio processor. */
    private val musicRemover: com.youtubly.app.music.MusicRemover,
    /** Offline resume points (saved every few seconds while a downloaded file plays). */
    private val downloadDao: com.youtubly.app.data.local.dao.DownloadDao
) {
    private val httpFactory: HttpDataSource.Factory = DefaultHttpDataSource.Factory()
        .setUserAgent(WEB_UA)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(20_000)

    private val dataSourceFactory: DataSource.Factory = ResolvingDataSource.Factory(httpFactory) { spec ->
        spec.withAdditionalHeaders(headersFor(spec.uri.toString()))
    }

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Human-readable playback errors (403, codec, network…) for the UI to show. */
    val errors: SharedFlow<String> = _errors

    private val _playbackEnded = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Fires once when the current item plays through to its natural end (not on pause/stop/
     *  seek). Used by LocalPlayerViewModel to advance to the next downloaded item — kept as a
     *  generic signal here rather than PlayerController knowing anything about a "queue". */
    val playbackEnded: SharedFlow<Unit> = _playbackEnded

    private val _streamExpired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** The current video's stream URLs stopped working; the owner should re-resolve and re-play. */
    val streamExpired: SharedFlow<Unit> = _streamExpired

    /** What the MediaSession controls: the player plus app-managed next/previous. */
    val sessionPlayer: SessionPlayer by lazy { SessionPlayer(player) }

    // ---------------- network loss / recovery ----------------
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var awaitingNetwork = false
    private val connectivity by lazy { context.getSystemService(android.net.ConnectivityManager::class.java) }

    private fun isOnline(): Boolean = runCatching {
        val caps = connectivity?.getNetworkCapabilities(connectivity?.activeNetwork)
        caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(true)

    private fun isConnectionError(e: PlaybackException) = e.errorCode in setOf(
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    ) || (e.errorCode == PlaybackException.ERROR_CODE_TIMEOUT && !GpuEffects.isRendererWedged(e))

    private fun isExpiredUrlError(e: PlaybackException): Boolean {
        if (e.errorCode != PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) return false
        val code = (e.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
        return code == null || code == 403 || code == 410
    }

    /** Fires when any network (Wi-Fi or mobile) comes up; resumes a player stopped by lost connection. */
    private val networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) {
            if (awaitingNetwork) mainHandler.postDelayed({ recover() }, 1_000) // let routing settle
        }
    }
    private var networkCallbackRegistered = false
    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) return
        networkCallbackRegistered = runCatching { connectivity?.registerDefaultNetworkCallback(networkCallback); true }.getOrDefault(false)
    }

    // ---------------- blur effect failure ----------------
    /** How many times the blur (GPU) effect failed on the current video. Reset per new video. */
    private var effectFailures = 0
    /** The blur effect is broken for the current video: load it without the effect. */
    private var effectsOffForVideo = false

    private fun effectsFor(uploaderUrl: String?, audioOnly: Boolean, videoId: String?): List<androidx.media3.common.Effect> {
        // Called right before every prepare(), so it's also where the music remover learns the channel.
        musicRemover.onNewItem(uploaderUrl)
        if (GpuEffects.isBroken(context)) return emptyList()
        return if (effectsOffForVideo) emptyList() else playbackBlur.effectsFor(uploaderUrl, audioOnly, videoId)
    }

    private var effectsPipelineUsed = false

    /** Reset before every prepare(); set by onRenderedFirstFrame. */
    private var firstFrameRendered = false

    /**
     * Video that never shows a frame while audio plays = the effects (blur) pipeline is stuck.
     * In a single-file source (downloads) that also starves the audio after a few seconds, since
     * both tracks come from the same extractor — the "plays a bit, black screen, then stops" bug.
     * Treat it like an effect crash instead of hanging silently.
     */
    private val videoStallCheck = Runnable {
        if (firstFrameRendered || isAudioMode) return@Runnable
        if (!player.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)) return@Runnable
        val effectsActive = effectsPipelineUsed && !effectsOffForVideo
        Log.w(TAG, "No video frame after ${VIDEO_STALL_TIMEOUT_MS}ms of playback (effects active=$effectsActive)")
        com.youtubly.app.core.ErrorLog.record("Player", IllegalStateException("Video never rendered a frame (effects=$effectsActive)"))
        if (effectsActive) { recoverFromEffectFailure(); return@Runnable }
        // No effect running yet still no picture: one clean reload at the same spot.
        if (!stallRetried) {
            stallRetried = true
            val pos = player.currentPosition
            player.stop()
            player.seekTo(pos)
            player.prepare()
            player.playWhenReady = true
        }
    }
    private var stallRetried = false

    private fun armVideoWatchdog() {
        firstFrameRendered = false
        stallRetried = false
        mainHandler.removeCallbacks(videoStallCheck)
    }

    /**
     * Media3 1.4 switches the video renderer to the GPU effects pipeline on the first
     * setVideoEffects() call — even with an empty list — and keeps it for the player's life.
     * So only call it when an effect is actually needed (or to clear one that was set).
     */
    /** Effects currently installed in the renderer; null = nothing installed / must be re-installed. */
    private var appliedEffects: List<androidx.media3.common.Effect>? = null

    private fun applyVideoEffects(effects: List<androidx.media3.common.Effect>) {
        if (effects.isEmpty() && !effectsPipelineUsed) return
        // Same effect instance(s) already installed and healthy: don't stop()+setVideoEffects() again.
        // That extra flush of a live GL pipeline is what triggers Media3's ExternalTextureManager race
        // (NoSuchElementException) and the follow-up "Detaching surface timed out" hang. The following
        // setMediaSource()+prepare() does the one flush that is actually needed.
        val applied = appliedEffects
        if (applied != null && applied.size == effects.size &&
            applied.indices.all { applied[it] === effects[it] }) return   // also covers empty == empty
        effectsPipelineUsed = true
        appliedEffects = effects
        // Swapping effects on a still-prepared player (previous video's GL graph mid-flush) is
        // what wedged the *next* video after a working one. Tear the old pipeline down first;
        // every caller sets a new source and prepare()s right after this anyway.
        if (player.playbackState != Player.STATE_IDLE) player.stop()
        player.setVideoEffects(effects)
    }

    /** Audio sink with the music remover in its processor chain (it stays inactive while off). */
    private val renderersFactory = object : androidx.media3.exoplayer.DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean
        ): androidx.media3.exoplayer.audio.AudioSink =
            androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                .also { android.util.Log.i("MusicRemover", "audio sink built with music remover") }
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf(com.youtubly.app.music.MusicRemoverAudioProcessor(musicRemover)))
                .build()
    }

    /** Preview lab: loop the next 20 s of the current video so processed vs original can be compared. */
    fun startMusicPreview(lengthMs: Long = 20_000L): Boolean {
        if (player.currentMediaItem == null) return false
        val a = player.currentPosition
        val dur = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val b = minOf(a + lengthMs, dur)
        if (b - a < MIN_LOOP_MS) return false
        _abLoop.value = AbLoop(a = a, b = b, enabled = true)
        syncLoopWatcher()
        player.playWhenReady = true
        return true
    }

    /**
     * "video frame processing failed" = the face-blur GPU effect crashed (audio keeps going, the
     * picture stops). Step 1: reload once with a fresh effect at <=1080p (4K / HDR are the usual
     * trigger). Step 2: if it fails again, drop the effect for this video — but only show the
     * picture unblurred when this video wouldn't be blurred anyway (blur off / whitelisted).
     * Otherwise keep faces hidden by playing audio only, and say so.
     */
    private fun recoverFromEffectFailure() {
        effectFailures++
        appliedEffects = null   // the old GL graph is dead: force a clean re-install on the next apply
        val info = currentInfo
        val pos = player.currentPosition
        val blurNeeded = playbackBlur.blurNeeded(info?.video?.uploaderUrl ?: currentLocalUploaderUrl, currentVideoId)
        when {
            effectFailures == 1 && info != null -> {
                Log.w(TAG, "Blur effect failed; retrying at <=1080p")
                runCatching { playInternal(info, currentOptions.copy(qualityHeight = currentOptions.qualityHeight?.coerceAtMost(1080)), pos, newVideo = false) }
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setMaxVideoSize(1920, 1080).build()
            }
            effectFailures == 1 && info == null && currentLocalVideoId != null -> {
                // Downloaded file: one clean retry with a fresh pipeline before giving up on the picture.
                Log.w(TAG, "Blur effect failed on local file; retrying once with a fresh pipeline")
                armVideoWatchdog()
                applyVideoEffects(effectsFor(currentLocalUploaderUrl, currentOptions.audioOnly, currentLocalVideoId))
                player.seekTo(pos)
                player.prepare(); player.playWhenReady = true
            }
            !blurNeeded -> {
                Log.w(TAG, "Blur effect failed again; not needed for this video, playing without it")
                effectsOffForVideo = true
                if (info != null) runCatching { playInternal(info, currentOptions, pos, newVideo = false) }
                else { applyVideoEffects(emptyList()); player.prepare(); player.playWhenReady = true }
            }
            else -> {
                Log.w(TAG, "Blur effect failed again; playing audio only to keep faces hidden")
                effectsOffForVideo = true
                videoDisabledByUser = true
                if (info != null) runCatching { playInternal(info, currentOptions, pos, newVideo = false) }
                else { applyVideoEffects(emptyList()); applyVideoTrackState(); player.prepare(); player.playWhenReady = true }
                _errors.tryEmit("Blur couldn't run on this video, so it's playing as audio only. Try a lower quality or the next video.")
            }
        }
    }

    /**
     * The playback thread is blocked inside the effects pipeline ("Detaching surface timed out"), so
     * nothing on this player works any more (black video, endless spinner, mini-player stuck).
     * Not a network problem — never retry it as one. A fresh process is the only reliable fix. If it
     * keeps happening, switch the GPU effects off for good rather than restart-looping.
     */
    private var wedgeHandled = false
    private fun handleWedgedRenderer(error: PlaybackException) {
        if (wedgeHandled) return
        wedgeHandled = true
        com.youtubly.app.core.ErrorLog.record("Player", error)
        // Repeated freezes pause the effects pipeline for 30 minutes (so we don't restart-loop); it's never
        // switched off for good on freezes alone, because a freeze isn't proof the driver can't do blur.
        val pause = GpuEffects.recordWedgeShouldDisable(context)
        if (pause) GpuEffects.suspendFor(context, 30 * 60_000L)
        Log.w(TAG, "Video renderer wedged (pauseEffects=$pause); restarting the app")
        android.widget.Toast.makeText(
            context,
            if (pause) "Video blur froze twice, so it's paused for 30 minutes. Restarting the player…"
            else "Video got stuck. Restarting the player…",
            android.widget.Toast.LENGTH_LONG
        ).show()
        mainHandler.postDelayed({ GpuEffects.restartApp(context) }, 1_200)
    }

    /**
     * Restarts a player that stopped on an error, at the same position. Safe to call any time:
     * does nothing unless the player is actually idle/errored. Stream videos rebuild their source
     * list (fresh connections, full fallback chain); local files just re-prepare.
     */
    fun recover() {
        if (player.playerError == null && player.playbackState != Player.STATE_IDLE) { awaitingNetwork = false; return }
        if (player.currentMediaItem == null) return
        awaitingNetwork = false
        val info = currentInfo
        val pos = player.currentPosition
        if (info != null) runCatching { playInternal(info, currentOptions, pos, newVideo = false) }
            .onFailure { player.prepare(); player.playWhenReady = true }
        else { player.prepare(); player.playWhenReady = true }
    }

    // ---------------- offline resume position ----------------
    private val ioScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    /** Download row of the local file playing now (single file or audio-library track). */
    private var currentLocalDownloadId: String? = null

    private val positionSaver = object : Runnable {
        override fun run() {
            saveLocalPosition()
            if (player.isPlaying) mainHandler.postDelayed(this, POSITION_SAVE_MS)
        }
    }

    /** Writes the playing local file's exact position (ignored for streamed videos / incognito). */
    fun saveLocalPosition() {
        val id = currentLocalDownloadId ?: return
        if (com.youtubly.app.core.Incognito.isOn) return
        val p = player
        if (p.mediaItemCount == 0) return
        val dur = p.duration
        if (dur <= 0) return
        val pos = p.currentPosition.coerceIn(0, dur)
        val pct = (pos * 100 / dur).toInt()
        ioScope.launch { runCatching { downloadDao.updatePosition(id, pos, dur, pct) } }
    }

    private val localPositionListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            mainHandler.removeCallbacks(positionSaver)
            if (isPlaying) mainHandler.postDelayed(positionSaver, POSITION_SAVE_MS) else saveLocalPosition()
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            // Audio library moved to another track: store where the old one ended / was left.
            val oldId = oldPosition.mediaItem?.mediaId?.takeIf { it.isNotEmpty() } ?: return
            if (oldId == newPosition.mediaItem?.mediaId) return
            if (com.youtubly.app.core.Incognito.isOn) return
            val pos = oldPosition.positionMs
            ioScope.launch {
                runCatching {
                    val dur = downloadDao.getById(oldId)?.lastDurationMs ?: 0L
                    val end = reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION
                    val at = if (end && dur > 0) dur else pos
                    downloadDao.updatePosition(oldId, at, dur, if (dur > 0) (at * 100 / dur).toInt().coerceIn(0, 100) else 0)
                }
            }
        }
    }

    val player: ExoPlayer by lazy {
        ExoPlayer.Builder(context, renderersFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            // Without these the MediaSession advertises no volume or seek buttons, so a paired watch
            // (Wear OS media controls), headset or car could only play/pause.
            .setDeviceVolumeControlEnabled(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            // Hold a Wi-Fi + CPU wake lock while playing, so audio keeps streaming with the
            // screen locked instead of stalling once the device dozes.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .also { registerNetworkCallback() }
            .also { it.addListener(localPositionListener) }
            .also { p ->
                p.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "Playback error ${error.errorCodeName}", error)
                        // Lost connection (Wi-Fi -> mobile data, tunnel, airplane mode): the stream
                        // itself is fine, so don't burn the fallback sources on it. Wait for a network
                        // and resume the same source at the same position (see networkCallback).
                        if (GpuEffects.isRendererWedged(error)) { handleWedgedRenderer(error); return }
                        if (error.errorCode == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED ||
                            GpuEffects.isEffectsCrash(error)) {
                            com.youtubly.app.core.ErrorLog.record("Player", error)
                            if (GpuEffects.isDriverFailure(error)) {
                                // Retrying can't help and the renderer is now stuck: remember it and
                                // start fresh, once. Blur in videos stays off on this phone afterwards.
                                Log.w(TAG, "GPU effects unsupported on this device; disabling video blur and restarting")
                                GpuEffects.markBroken(context)
                                android.widget.Toast.makeText(context, "Video blur isn't supported by this phone's graphics. Restarting the player…", android.widget.Toast.LENGTH_LONG).show()
                                mainHandler.postDelayed({ GpuEffects.restartApp(context) }, 1_500)
                                return
                            }
                            recoverFromEffectFailure()
                            return
                        }
                        if (isConnectionError(error)) {
                            awaitingNetwork = true
                            if (isOnline()) mainHandler.postDelayed({ recover() }, 1_000)
                            return
                        }
                        if (tryNextSource()) return
                        // Every source refused (403/410): YouTube stream URLs are short-lived and
                        // expire while the app sits in the background. Ask for fresh ones.
                        if (currentInfo != null && isExpiredUrlError(error)) { _streamExpired.tryEmit(Unit); return }
                        com.youtubly.app.core.ErrorLog.record("Player", error)
                        _errors.tryEmit("Playback failed: ${error.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')}")
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) _playbackEnded.tryEmit(Unit)
                    }

                    override fun onRenderedFirstFrame() {
                        firstFrameRendered = true
                        mainHandler.removeCallbacks(videoStallCheck)
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying && !firstFrameRendered) {
                            mainHandler.removeCallbacks(videoStallCheck)
                            mainHandler.postDelayed(videoStallCheck, VIDEO_STALL_TIMEOUT_MS)
                        }
                    }

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        onLocalTransition(mediaItem)
                    }

                    override fun onAudioSessionIdChanged(audioSessionId: Int) {
                        // A new AudioTrack session means the old LoudnessEnhancer is dead.
                        attachLoudness()
                    }
                })
            }
    }

    private var sessionControllerStarted = false
    private var pendingFallbacks: MutableList<MediaSource> = mutableListOf()
    private var resumePositionMs = 0L
    // Held for the process lifetime: an un-retained MediaController future is GC'd almost
    // immediately, which silently tears down the binder link to PlaybackService and kills
    // background/lock-screen playback within seconds of leaving the app.
    private var sessionControllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null

    /** Binds to PlaybackService so playback continues in the background with a media notification. */
    fun ensureSessionService() {
        if (sessionControllerStarted) return
        sessionControllerStarted = true
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token)
            // If the service dies or the connection drops, allow the next play() to bind again;
            // before, this ran once per process and a dropped session stayed dead (no notification,
            // no watch / headset controls) until the app was restarted.
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    Log.w(TAG, "Session controller disconnected; will rebind on next playback")
                    sessionControllerStarted = false
                }
            })
            .buildAsync()
        sessionControllerFuture = future
        future.addListener({
            if (future.isCancelled || runCatching { future.get() }.isFailure) {
                Log.w(TAG, "Session controller failed to connect; will retry on next playback")
                sessionControllerStarted = false
            }
        }, com.google.common.util.concurrent.MoreExecutors.directExecutor())
    }

    private var currentInfo: StreamInfoResult? = null
    /** The streamed video currently loaded in the player, if any (null for local files). */
    val loadedInfo: StreamInfoResult? get() = currentInfo
    /** Set instead of currentInfo while playing a locally saved (downloaded) file. */
    private var currentLocalVideoId: String? = null
    /** Channel of the local file playing (blur exclusions); null = unknown. */
    private var currentLocalUploaderUrl: String? = null
    var currentOptions: PlaybackOptions = PlaybackOptions()
        private set

    /** Id of the video currently loaded in the player (null when idle) — streamed or local. */
    val currentVideoId: String? get() = currentInfo?.video?.id ?: currentLocalVideoId
    /** Watch URL of the streamed video (null for local files) — used by the widget. */
    val currentVideoUrl: String? get() = currentInfo?.video?.url
    val currentThumbnailUrl: String? get() = currentInfo?.video?.thumbnailUrl

    private val localDataSourceFactory: DataSource.Factory by lazy { DefaultDataSource.Factory(context) }

    /**
     * Plays a file already saved on-device (from the Downloads tab) through this same
     * singleton player — so it gets the same background-playback service, media-session
     * notification, and picture-in-picture support as streamed videos, instead of being
     * handed off to an external app with none of that wired up.
     */
    fun playLocal(filePath: String, title: String, isAudioOnly: Boolean, videoId: String, thumbnailUrl: String? = null, uploaderUrl: String? = null, startPositionMs: Long = 0L) {
        currentLocalUploaderUrl = uploaderUrl
        _audioNowPlaying.value = null; player.shuffleModeEnabled = false
        ensureSessionService()
        _queue.value = null // downloads have their own next-item logic
        _nowPlayingId.value = null
        currentInfo = null
        currentLocalVideoId = videoId
        currentLocalDownloadId = videoId   // LocalPlayerViewModel passes the download id here
        currentOptions = PlaybackOptions(audioOnly = isAudioOnly)
        effectFailures = 0; effectsOffForVideo = false
        clearLoop()
        videoDisabledByUser = false
        videoDisabledForBackground = false
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtworkUri(thumbnailUrl?.let(Uri::parse))
            .build()
        val item = MediaItem.Builder().setUri(Uri.fromFile(java.io.File(filePath))).setMediaMetadata(metadata).build()
        val source = ProgressiveMediaSource.Factory(localDataSourceFactory).createMediaSource(item)
        Log.d(TAG, "Playing local file '$title'")
        pendingFallbacks = mutableListOf()
        resumePositionMs = 0L
        armVideoWatchdog()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
            .setPreferredAudioLanguages()
            .setPreferredAudioRoleFlags(C.ROLE_FLAG_MAIN)
            .clearVideoSizeConstraints()
            .build()
        _audioMode.value = isAudioMode
        applyVideoEffects(effectsFor(uploaderUrl = uploaderUrl, audioOnly = isAudioOnly, videoId = videoId))
        player.setMediaSource(source, startPositionMs.coerceAtLeast(0L))
        player.prepare()
        attachLoudness()
        player.playWhenReady = true
    }

    // ---------------- audio library (downloaded files as a playlist) ----------------

    /** One downloaded file for [playLocalPlaylist]. */
    data class LocalTrack(val downloadId: String, val videoId: String, val title: String, val filePath: String, val thumbnailUrl: String?, val uploaderUrl: String? = null, val resumeAtMs: Long? = null)

    /** What the audio library is playing right now (null = not in audio-library mode). */
    data class AudioNowPlaying(val tracks: List<LocalTrack>, val index: Int) {
        val current: LocalTrack? get() = tracks.getOrNull(index)
    }
    private val _audioNowPlaying = MutableStateFlow<AudioNowPlaying?>(null)
    val audioNowPlaying: StateFlow<AudioNowPlaying?> = _audioNowPlaying

    /**
     * Plays downloaded files back-to-back as **audio** (video tracks disabled, so video
     * downloads work too). The whole list is handed to ExoPlayer as one playlist, so next /
     * previous and auto-advance also work from the notification and the lock screen.
     */
    fun playLocalPlaylist(tracks: List<LocalTrack>, startIndex: Int = 0, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        ensureSessionService()
        sessionPlayer.navigator = null // a real ExoPlayer playlist: native next/previous
        _queue.value = null
        _nowPlayingId.value = null
        currentInfo = null
        clearLoop()
        videoDisabledByUser = false
        videoDisabledForBackground = false
        currentOptions = PlaybackOptions(audioOnly = true)
        pendingFallbacks = mutableListOf()
        resumePositionMs = 0L
        val sources = tracks.map { t ->
            val item = MediaItem.Builder()
                .setMediaId(t.downloadId)
                .setUri(Uri.fromFile(java.io.File(t.filePath)))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(t.title).setArtworkUri(t.thumbnailUrl?.let(Uri::parse)).build())
                .build()
            ProgressiveMediaSource.Factory(localDataSourceFactory).createMediaSource(item)
        }
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            .build()
        val start = startIndex.coerceIn(0, tracks.lastIndex)
        currentLocalVideoId = tracks[start].videoId
        currentLocalDownloadId = tracks[start].downloadId
        currentLocalUploaderUrl = tracks[start].uploaderUrl
        // Tell the music remover which channel this is, so Excluded channels keep their music.
        // (Single-file playback does this through effectsFor(); the playlist path skipped it.)
        musicRemover.onNewItem(tracks[start].uploaderUrl)
        _audioNowPlaying.value = AudioNowPlaying(tracks, start)
        _audioMode.value = true
        player.shuffleModeEnabled = shuffle
        applyVideoEffects(emptyList())   // audio library: no video, no blur pipeline
        player.setMediaSources(sources, start, tracks[start].resumeAtMs ?: 0L)   // continue where it was left
        player.prepare()
        attachLoudness()
        player.playWhenReady = true
        Log.d(TAG, "Audio library: ${tracks.size} track(s), start=$start, shuffle=$shuffle")
    }

    fun setAudioShuffle(on: Boolean) { player.shuffleModeEnabled = on }

    /** Keeps [audioNowPlaying] / currentVideoId in step as ExoPlayer moves through the list. */
    private fun onLocalTransition(item: MediaItem?) {
        val np = _audioNowPlaying.value ?: return
        val idx = np.tracks.indexOfFirst { it.downloadId == item?.mediaId }
        if (idx >= 0) {
            val previous = np.index
            _audioNowPlaying.value = np.copy(index = idx)
            currentLocalVideoId = np.tracks[idx].videoId
            currentLocalDownloadId = np.tracks[idx].downloadId
            currentLocalUploaderUrl = np.tracks[idx].uploaderUrl
            // Every track in a playlist can be from a different channel.
            if (idx != previous) musicRemover.onNewItem(np.tracks[idx].uploaderUrl)
        }
    }

    /** @param newVideo false when re-opening the same video (quality change, fresh URLs) — keeps
     *  the A–B loop and the user's audio-mode choice. */
    fun play(info: StreamInfoResult, options: PlaybackOptions = PlaybackOptions(), startPositionMs: Long = 0L, newVideo: Boolean = true) {
        sessionPlayer.navigator = null // streaming: the downloads queue no longer applies
        currentLocalDownloadId = null
        playInternal(info, options, startPositionMs, newVideo)
    }

    private fun playInternal(info: StreamInfoResult, options: PlaybackOptions, startPositionMs: Long, newVideo: Boolean) {
        _audioNowPlaying.value = null; player.shuffleModeEnabled = false
        ensureSessionService()
        if (newVideo || currentInfo?.video?.id != info.video.id) {
            effectFailures = 0; effectsOffForVideo = false
            clearLoop()
            videoDisabledByUser = false
            videoDisabledForBackground = false
        }
        if (GpuEffects.isBroken(context) && !options.audioOnly && !videoDisabledByUser &&
            playbackBlur.blurNeeded(info.video.uploaderUrl, info.video.id)) {
            // No GPU blur on this phone: keep faces hidden the only safe way, like the fallback does.
            videoDisabledByUser = true
            _errors.tryEmit("Video blur isn't supported on this phone, so faces are hidden by playing audio only. Turn off Content blur → \"Video playback\" (or exclude the channel) to watch.")
        }
        currentInfo = info
        currentLocalVideoId = null
        currentOptions = options
        _nowPlayingId.value = info.video.id
        val candidates = buildCandidates(info, options)
        if (candidates.isEmpty()) error("No playable stream found for this video")
        Log.d(TAG, "Playing '${info.video.title}' with ${candidates.size} candidate source(s), options=$options")
        pendingFallbacks = candidates.drop(1).toMutableList()
        resumePositionMs = startPositionMs
        armVideoWatchdog()
        // Original audio only: never let a dub (or the phone's language) win in HLS/DASH.
        val originalLang = info.audioStreams.firstOrNull { it.isOriginal && it.languageTag != null }?.languageTag
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguages(*listOfNotNull(originalLang).toTypedArray())
            .setPreferredAudioRoleFlags(C.ROLE_FLAG_MAIN)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, options.caption == null)
            .setPreferredTextLanguage(options.translateTo ?: options.caption?.languageTag)
            .setSelectUndeterminedTextLanguage(true)
            .apply {
                val cap = options.maxHeight
                if (options.qualityHeight == null && cap != null) setMaxVideoSize(Int.MAX_VALUE, cap)
                else clearVideoSizeConstraints()
            }
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, videoDisabledByUser || videoDisabledForBackground)
            .build()
        _audioMode.value = isAudioMode
        // Before prepare(): effects are wired into the renderer when the source is prepared.
        applyVideoEffects(effectsFor(info.video.uploaderUrl, options.audioOnly, info.video.id))
        player.setMediaSource(candidates.first(), startPositionMs)
        player.prepare()
        attachLoudness()
        player.playWhenReady = true
    }

    /** Re-open the current video with new quality / captions, keeping the position. */
    fun reconfigure(options: PlaybackOptions) {
        val info = currentInfo ?: return
        val wasPlaying = player.playWhenReady
        playInternal(info, options, player.currentPosition, newVideo = false)
        player.playWhenReady = wasPlaying
    }

    /** Halts playback and clears the media source — e.g. before deleting the file currently
     *  playing, so nothing keeps reading from a path that's about to be unlinked. */
    fun stop() {
        sessionPlayer.navigator = null
        _audioNowPlaying.value = null; player.shuffleModeEnabled = false
        _queue.value = null
        _nowPlayingId.value = null
        pendingFallbacks = mutableListOf()
        currentInfo = null
        currentLocalVideoId = null
        clearLoop()
        videoDisabledByUser = false
        videoDisabledForBackground = false
        player.stop()
        player.clearMediaItems()
        releaseLoudness()
        _audioMode.value = false
    }

    fun setSpeed(speed: Float) = player.setPlaybackSpeed(speed)

    // ---------------- queue (Phase 6) ----------------
    private val _queue = MutableStateFlow<PlayQueue?>(null)
    val queue: StateFlow<PlayQueue?> = _queue

    /** Starts a queue at [startIndex]; the caller then opens the player for that item. */
    fun setQueue(title: String, items: List<com.youtubly.app.domain.model.Video>, startIndex: Int = 0) {
        _queue.value = if (items.isEmpty()) null else PlayQueue(title, items, startIndex.coerceIn(0, items.lastIndex))
    }

    /** Moves to the next item and returns it, or null at the end (queue unchanged). */
    fun queueNext(): com.youtubly.app.domain.model.Video? {
        val q = _queue.value ?: return null
        if (!q.hasNext) return null
        return q.copy(index = q.index + 1).also { _queue.value = it }.current
    }

    fun queuePrevious(): com.youtubly.app.domain.model.Video? {
        val q = _queue.value ?: return null
        if (!q.hasPrevious) return null
        return q.copy(index = q.index - 1).also { _queue.value = it }.current
    }

    fun clearQueue() { _queue.value = null }

    /**
     * Puts [video] right after the current item. With no queue but a streamed video playing,
     * starts a small "Up next" queue of [current, video]. Returns false when nothing is
     * playing (the caller should just play it instead).
     */
    fun playNext(video: com.youtubly.app.domain.model.Video): Boolean {
        val q = _queue.value
        if (q != null) {
            val items = q.items.toMutableList().apply { add((q.index + 1).coerceAtMost(size), video) }
            _queue.value = q.copy(items = items)
            return true
        }
        val current = currentInfo?.video ?: return false
        if (current.id == video.id) return true
        _queue.value = PlayQueue("Up next", listOf(current, video), 0)
        return true
    }

    private val _nowPlayingId = MutableStateFlow<String?>(null)
    /** Id of the streamed video loaded in the player (null when stopped or playing a download).
     *  Lets a visible player screen follow a queue advance made elsewhere. */
    val nowPlayingId: StateFlow<String?> = _nowPlayingId

    // ---------------- audio mode (video track off, no reload) ----------------
    private var videoDisabledByUser = false
    private var videoDisabledForBackground = false

    private val _audioMode = MutableStateFlow(false)
    /** True while no video is being shown/downloaded: user "Listen only", audio-only source,
     *  or the video track dropped because the screen is off. */
    val audioMode: StateFlow<Boolean> = _audioMode

    /** User-visible audio mode (drives the overlay, PiP eligibility, mini player). */
    val isAudioMode: Boolean get() = currentOptions.audioOnly || videoDisabledByUser

    /** True if the loaded source has a video track that can simply be switched back on. */
    val canRestoreVideoInPlace: Boolean get() = !currentOptions.audioOnly

    /**
     * Turns the video track off/on without reloading: ExoPlayer stops selecting (and so stops
     * downloading) video, while audio keeps its buffer and position. Adaptive sources (HLS)
     * fall back to their audio rendition; merged video+audio just stops loading the video half.
     */
    fun setVideoDisabled(disabled: Boolean) {
        videoDisabledByUser = disabled
        applyVideoTrackState()
    }

    private fun applyVideoTrackState() {
        val off = videoDisabledByUser || videoDisabledForBackground
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, off)
            .build()
        _audioMode.value = isAudioMode
    }

    /** App went to the background or the screen turned off (not a PiP window). */
    fun onAppBackgrounded(keepAudio: Boolean) {
        if (currentVideoId == null) return
        if (!keepAudio) {
            player.pause()
            return
        }
        // Local files cost no data — leave them alone.
        if (currentInfo != null && !videoDisabledByUser && !currentOptions.audioOnly) {
            videoDisabledForBackground = true
            applyVideoTrackState()
        }
    }

    fun onAppForegrounded() {
        if (videoDisabledForBackground) {
            videoDisabledForBackground = false
            applyVideoTrackState()
        }
    }

    // ---------------- skip silence + volume boost ----------------
    private val _skipSilence = MutableStateFlow(false)
    val skipSilence: StateFlow<Boolean> = _skipSilence

    fun setSkipSilence(enabled: Boolean) {
        _skipSilence.value = enabled
        player.skipSilenceEnabled = enabled
    }

    private val _boostDb = MutableStateFlow(0)
    /** Loudness boost in dB; the UI offers [VolumeBoost.LEVELS_DB]. */
    val boostDb: StateFlow<Int> = _boostDb
    private var loudness: android.media.audiofx.LoudnessEnhancer? = null
    private var loudnessSessionId = C.AUDIO_SESSION_ID_UNSET

    /** The boost the user chose themselves; a channel's own boost is applied on top and never replaces it. */
    private var userBoostDb = 0

    fun setBoostDb(db: Int) {
        userBoostDb = db.coerceIn(0, VolumeBoost.MAX_DB)
        _boostDb.value = userBoostDb
        attachLoudness()
    }

    /**
     * A channel's own sound for the video starting now: [boostDb] and [presetName] (an EqPreset name), or null to fall
     * back to what the user picked. Temporary: nothing is saved, so the next channel starts from the user's own choice again.
     */
    fun applyChannelSound(boostDb: Int?, presetName: String?) {
        _boostDb.value = (boostDb ?: userBoostDb).coerceIn(0, VolumeBoost.MAX_DB)
        val own = com.youtubly.app.domain.audio.EqPreset.fromName(fxPrefs.getString("eq_preset", null))
        _eqPreset.value = presetName?.let { com.youtubly.app.domain.audio.EqPreset.fromName(it) } ?: own
        attachLoudness()
    }

    // ---- equalizer (remembered across launches) ----
    private val fxPrefs by lazy { context.getSharedPreferences("audio_fx", Context.MODE_PRIVATE) }
    private val _eqPreset by lazy { MutableStateFlow(com.youtubly.app.domain.audio.EqPreset.fromName(fxPrefs.getString("eq_preset", null))) }
    val eqPreset: StateFlow<com.youtubly.app.domain.audio.EqPreset> get() = _eqPreset
    private var equalizer: android.media.audiofx.Equalizer? = null
    private var equalizerSessionId = C.AUDIO_SESSION_ID_UNSET

    fun setEqPreset(preset: com.youtubly.app.domain.audio.EqPreset) {
        _eqPreset.value = preset
        fxPrefs.edit().putString("eq_preset", preset.name).apply()
        attachEqualizer()
    }

    private fun attachEqualizer() {
        val preset = _eqPreset.value
        if (preset == com.youtubly.app.domain.audio.EqPreset.OFF) { releaseEqualizer(); return }
        val session = player.audioSessionId
        if (session == C.AUDIO_SESSION_ID_UNSET || session == 0) return
        runCatching {
            if (equalizer == null || equalizerSessionId != session) {
                releaseEqualizer()
                equalizer = android.media.audiofx.Equalizer(0, session)
                equalizerSessionId = session
            }
            val eq = equalizer ?: return
            val range = eq.bandLevelRange
            for (band in 0 until eq.numberOfBands) {
                eq.setBandLevel(band.toShort(), preset.bandLevelMb(eq.getCenterFreq(band.toShort()), range[0].toInt(), range[1].toInt()).toShort())
            }
            eq.enabled = true
        }.onFailure {
            Log.w(TAG, "Equalizer unavailable: ${it.message}")
            releaseEqualizer()
        }
    }

    private fun releaseEqualizer() {
        runCatching { equalizer?.enabled = false; equalizer?.release() }
        equalizer = null
        equalizerSessionId = C.AUDIO_SESSION_ID_UNSET
    }

    /** Loudness + equalizer both live on the player's audio session; re-attach them together. */
    private fun attachLoudness() {
        attachLoudnessOnly()
        attachEqualizer()
    }

    private fun attachLoudnessOnly() {
        val db = _boostDb.value
        if (db <= 0) { releaseLoudness(); return }
        val session = player.audioSessionId
        if (session == C.AUDIO_SESSION_ID_UNSET || session == 0) return
        runCatching {
            if (loudness == null || loudnessSessionId != session) {
                releaseLoudness()
                loudness = android.media.audiofx.LoudnessEnhancer(session)
                loudnessSessionId = session
            }
            loudness?.setTargetGain(db * 100) // millibels
            loudness?.setEnabled(true)
        }.onFailure {
            Log.w(TAG, "LoudnessEnhancer unavailable: ${it.message}")
            releaseLoudness()
        }
    }

    private fun releaseLoudness() {
        runCatching { loudness?.setEnabled(false); loudness?.release() }
        loudness = null
        loudnessSessionId = C.AUDIO_SESSION_ID_UNSET
    }

    // ---------------- A–B loop ----------------
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _abLoop = MutableStateFlow(AbLoop())
    val abLoop: StateFlow<AbLoop> = _abLoop
    private var loopJob: Job? = null

    fun setLoopA() {
        val pos = player.currentPosition
        val b = _abLoop.value.b?.takeIf { it > pos + MIN_LOOP_MS }
        _abLoop.value = AbLoop(a = pos, b = b, enabled = _abLoop.value.enabled && b != null)
        syncLoopWatcher()
    }

    /** Returns false when B isn't at least half a second after A. */
    fun setLoopB(): Boolean {
        val pos = player.currentPosition
        val a = _abLoop.value.a ?: 0L
        if (pos <= a + MIN_LOOP_MS) return false
        // Setting B turns the loop on — that's almost always what the user wants next.
        _abLoop.value = AbLoop(a = a, b = pos, enabled = true)
        syncLoopWatcher()
        return true
    }

    fun setLoopEnabled(enabled: Boolean) {
        _abLoop.value = _abLoop.value.copy(enabled = enabled)
        syncLoopWatcher()
    }

    fun clearLoop() {
        _abLoop.value = AbLoop()
        syncLoopWatcher()
    }

    private fun syncLoopWatcher() {
        loopJob?.cancel()
        loopJob = null
        if (!_abLoop.value.isActive) return
        loopJob = mainScope.launch {
            while (isActive) {
                val loop = _abLoop.value
                val a = loop.a
                val b = loop.b
                if (loop.isActive && a != null && b != null) {
                    val pos = player.currentPosition
                    if (pos >= b) player.seekTo(a)
                }
                delay(LOOP_TICK_MS)
            }
        }
    }

    /** Resolutions offered in the quality menu, highest first (e.g. 2160, 1440, 1080 …). */
    fun availableHeights(info: StreamInfoResult): List<Int> =
        info.videoStreams.filter { it.isVideoOnly && it.height > 0 }.map { it.height }.distinct().sortedDescending()

    /** On a playback error, silently move on to the next candidate source. */
    private fun tryNextSource(): Boolean {
        val next = pendingFallbacks.removeFirstOrNull() ?: return false
        Log.w(TAG, "Falling back to next source (${pendingFallbacks.size} left)")
        val pos = player.currentPosition.takeIf { it > 0 } ?: resumePositionMs
        // Re-prepare re-creates the render pipeline, so effects must be set again here too — every
        // other prepare() call in this class does the same right before it. Skipping this was letting
        // a fallback resume through whatever GL effect state the failed source left behind.
        currentInfo?.let { info -> applyVideoEffects(effectsFor(info.video.uploaderUrl, currentOptions.audioOnly, info.video.id)) }
        player.setMediaSource(next, pos)
        player.prepare()
        player.playWhenReady = true
        return true
    }

    /**
     * Ordered best-first. Auto quality:
     * 1. HLS (adaptive, audio+video together; what the iOS client returns — very reliable)
     * 2. DASH manifest (live / post-live)
     * 3. Best video-only (≤1080p) merged with best audio
     * 4. Legacy muxed progressive (usually 360p)
     * A fixed quality puts "video-only at that height + audio" first, then the auto chain.
     * The chosen caption track is merged into every candidate.
     */
    private fun buildCandidates(info: StreamInfoResult, options: PlaybackOptions): List<MediaSource> {
        val metadata = MediaMetadata.Builder()
            .setTitle(info.video.title)
            .setArtist(info.video.uploaderName)
            .setArtworkUri(info.video.thumbnailUrl?.let(Uri::parse))
            .build()
        fun item(url: String) = MediaItem.Builder().setUri(url).setMediaMetadata(metadata).build()
        fun progressive(url: String) = ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(item(url))

        val list = buildStreamPlans(info, options).map { plan ->
            when (plan) {
                is StreamPlan.Progressive -> progressive(plan.url)
                is StreamPlan.VideoPlusAudio -> MergingMediaSource(progressive(plan.videoUrl), progressive(plan.audioUrl))
                is StreamPlan.Hls -> HlsMediaSource.Factory(dataSourceFactory).createMediaSource(item(plan.url))
                is StreamPlan.Dash -> DashMediaSource.Factory(dataSourceFactory).createMediaSource(item(plan.url))
            }
        }

        val caption = options.caption ?: return list
        val captionUrl = caption.url + (options.translateTo?.let { "&tlang=$it" } ?: "")
        val lang = options.translateTo ?: caption.languageTag
        return list.map { source ->
            val sub = MediaItem.SubtitleConfiguration.Builder(Uri.parse(captionUrl))
                .setMimeType(MimeTypes.TEXT_VTT)
                .setLanguage(lang)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
            MergingMediaSource(
                source,
                SingleSampleMediaSource.Factory(dataSourceFactory)
                    .setTreatLoadErrorsAsEndOfStream(true)
                    .createMediaSource(sub, C.TIME_UNSET)
            )
        }
    }


    private fun headersFor(url: String): Map<String, String> {
        val client = CLIENT_REGEX.find(url)?.groupValues?.get(1)?.uppercase()
        return when (client) {
            "ANDROID", "ANDROID_VR" -> mapOf("User-Agent" to YoutubeParsingHelper.getAndroidUserAgent(null))
            "IOS" -> mapOf("User-Agent" to YoutubeParsingHelper.getIosUserAgent(null))
            "VISIONOS" -> mapOf("User-Agent" to YoutubeParsingHelper.getVisionOsUserAgent(null))
            else -> mapOf(
                "User-Agent" to WEB_UA,
                "Origin" to "https://www.youtube.com",
                "Referer" to "https://www.youtube.com/"
            )
        }
    }

    private companion object {
        const val TAG = "YoutublyPlayer"
        const val LOOP_TICK_MS = 200L
        const val POSITION_SAVE_MS = 5_000L
        const val MIN_LOOP_MS = 500L
        /** Audio playing this long with no video frame = stuck video pipeline. */
        const val VIDEO_STALL_TIMEOUT_MS = 4_000L
        const val WEB_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
        // Matches "&c=IOS" in stream URLs and "/c/IOS/" in HLS manifest paths.
        val CLIENT_REGEX = Regex("[?&/]c[=/]([A-Za-z_]+)")
    }
}
