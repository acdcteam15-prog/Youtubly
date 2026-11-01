package com.youtubly.app.widget

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.SuccessResult
import coil3.toBitmap
import com.youtubly.app.data.local.dao.WatchHistoryDao
import com.youtubly.app.playback.PlaybackService
import com.youtubly.app.playback.PlayerController
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Hilt singletons for code the framework instantiates (widget, receiver, callbacks). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun watchHistoryDao(): WatchHistoryDao
    fun playerController(): PlayerController
    fun videoRepository(): com.youtubly.app.domain.repository.VideoRepository
    fun userPreferences(): com.youtubly.app.data.local.datastore.UserPreferences
}

data class NowPlaying(val title: String, val subtitle: String?, val isPlaying: Boolean, val videoUrl: String?, val thumb: Bitmap?)
data class ContinueItem(val title: String, val channel: String, val url: String, val remainingSec: Long, val progress: Float, val thumb: Bitmap?, val startMs: Long = 0L)
data class WidgetState(
    val nowPlaying: NowPlaying?,
    val continueItem: ContinueItem?,
    /** The app's accent (or the wallpaper accent when Material You is on). */
    val accent: androidx.compose.ui.graphics.Color = com.youtubly.app.presentation.theme.Ember
)

object WidgetData {

    private fun entry(context: Context) =
        EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)

    suspend fun load(context: Context): WidgetState {
        val ep = entry(context)
        val nowPlaying = withContext(Dispatchers.Main) {
            val pc = ep.playerController()
            // Checking the id first avoids creating the (lazy) ExoPlayer when nothing is loaded.
            if (pc.currentVideoId == null) return@withContext null
            val p = pc.player
            if (p.mediaItemCount == 0) return@withContext null
            val md = p.mediaMetadata
            NowPlaying(
                title = md.title?.toString() ?: "Playing",
                subtitle = md.artist?.toString(),
                isPlaying = p.playWhenReady,
                videoUrl = pc.currentVideoUrl,
                thumb = null
            ) to (pc.currentThumbnailUrl ?: md.artworkUri?.toString())
        }?.let { (np, thumbUrl) -> np.copy(thumb = loadThumb(context, thumbUrl)) }

        val cont = runCatching {
            ep.watchHistoryDao().recentInProgress(5).firstOrNull { e ->
                val durMs = e.durationSec * 1000
                durMs <= 0 || e.lastPositionMs < durMs - 15_000 // skip basically-finished videos
            }
        }.getOrNull()?.let { e ->
            val durMs = e.durationSec * 1000
            ContinueItem(
                title = e.title,
                channel = e.uploaderName,
                url = e.url,
                remainingSec = if (durMs > 0) (durMs - e.lastPositionMs) / 1000 else 0,
                progress = if (durMs > 0) (e.lastPositionMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f,
                thumb = loadThumb(context, e.thumbnailUrl),
                startMs = e.lastPositionMs
            )
        }
        return WidgetState(nowPlaying, cont, loadAccent(context, ep))
    }

    private suspend fun loadAccent(context: Context, ep: WidgetEntryPoint): androidx.compose.ui.graphics.Color =
        runCatching {
            val prefs = ep.userPreferences()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
                prefs.dynamicColor.first()
            ) {
                // Same tone the dark dynamic scheme uses for primary.
                androidx.compose.ui.graphics.Color(context.getColor(android.R.color.system_accent1_200))
            } else {
                val idx = prefs.accent.first()
                (com.youtubly.app.presentation.theme.Accents.getOrNull(idx)
                    ?: com.youtubly.app.presentation.theme.Accents.first()).color
            }
        }.getOrDefault(com.youtubly.app.presentation.theme.Ember)

    /** Small bitmap (RemoteViews have a size budget), software-backed so it can be parcelled. */
    private suspend fun loadThumb(context: Context, url: String?): Bitmap? {
        url ?: return null
        return runCatching {
            val req = ImageRequest.Builder(context).data(url).size(320, 180).allowHardware(false).build()
            (context.imageLoader.execute(req) as? SuccessResult)?.image?.toBitmap(320, 180, Bitmap.Config.RGB_565)
        }.getOrNull()
    }

    /**
     * Play/pause. The widget's callback runs inside the app process, so it drives the same singleton
     * player the app uses, with no MediaController round-trip (that bind could time out or connect
     * before the session had a player, and the button then silently did nothing).
     * Also restarts a finished or stopped item instead of ignoring the tap.
     */
    suspend fun togglePlayPause(context: Context) = withContext(Dispatchers.Main) {
        val pc = entry(context).playerController()
        if (pc.currentVideoId == null) return@withContext
        val p = pc.player
        if (p.mediaItemCount == 0) return@withContext
        pc.ensureSessionService()   // keeps playback alive in the background / shows the notification
        when {
            p.playWhenReady && p.playbackState != androidx.media3.common.Player.STATE_ENDED &&
                p.playbackState != androidx.media3.common.Player.STATE_IDLE -> p.pause()
            p.playbackState == androidx.media3.common.Player.STATE_ENDED -> { p.seekTo(0); p.play() }
            p.playbackState == androidx.media3.common.Player.STATE_IDLE -> { p.prepare(); p.play() }
            else -> p.play()
        }
    }

    /**
     * "Continue watching" ▶: start that video without opening the app, from where it was left off.
     * It plays as background audio (there's no screen to draw on); tapping the thumbnail opens it
     * with video. Returns false when it couldn't start (offline, extractor failure…).
     */
    suspend fun playContinue(context: Context, url: String, startMs: Long): Boolean {
        val ep = entry(context)
        val info = ep.videoRepository().getStreamInfo(url).getOrNull() ?: return false
        return withContext(Dispatchers.Main) {
            ep.playerController().play(
                info,
                com.youtubly.app.playback.PlaybackOptions(audioOnly = true),
                startPositionMs = startMs,
                newVideo = true
            )
            true
        }
    }
}
