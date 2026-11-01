package com.youtubly.app.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.youtubly.app.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps audio (and video) playing once the app is backgrounded or the screen locks.
 * MediaSessionService automatically promotes itself to a foreground service with a
 * system media-style notification as soon as the player starts playing; this class
 * only owns the MediaSession and decides when it's safe to actually stop.
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var playerController: PlayerController
    private var mediaSession: MediaSession? = null

    // ---- Home-screen widget refresh ----
    private val widgetScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
    private var widgetJob: kotlinx.coroutines.Job? = null

    /** Debounced: seeking/buffering fire bursts of events; one redraw ~0.5 s later is enough. */
    private fun refreshWidget() {
        widgetJob?.cancel()
        widgetJob = widgetScope.launch {
            kotlinx.coroutines.delay(500)
            com.youtubly.app.widget.YoutublyWidget.refresh(this@PlaybackService)
            com.youtubly.app.widget.YoutublyTileService.requestRefresh(this@PlaybackService)
        }
    }

    private val widgetListener = object : androidx.media3.common.Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = refreshWidget()
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = refreshWidget()
        override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) = refreshWidget()
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == androidx.media3.common.Player.STATE_IDLE || playbackState == androidx.media3.common.Player.STATE_ENDED) refreshWidget()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val sessionActivity = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // ±10 s buttons next to play/pause: shown on the notification, lock screen and Wear OS media
        // controls (single videos have no next/previous, so the watch otherwise had only play/pause).
        val seekButtons = com.google.common.collect.ImmutableList.of(
            androidx.media3.session.CommandButton.Builder(androidx.media3.session.CommandButton.ICON_SKIP_BACK_10)
                .setPlayerCommand(androidx.media3.common.Player.COMMAND_SEEK_BACK)
                .setDisplayName("Back 10 seconds")
                .setSlots(androidx.media3.session.CommandButton.SLOT_BACK_SECONDARY)
                .build(),
            androidx.media3.session.CommandButton.Builder(androidx.media3.session.CommandButton.ICON_SKIP_FORWARD_10)
                .setPlayerCommand(androidx.media3.common.Player.COMMAND_SEEK_FORWARD)
                .setDisplayName("Forward 10 seconds")
                .setSlots(androidx.media3.session.CommandButton.SLOT_FORWARD_SECONDARY)
                .build()
        )
        mediaSession = MediaSession.Builder(this, playerController.sessionPlayer)
            .setSessionActivity(sessionActivity)
            .setMediaButtonPreferences(seekButtons)
            .setCallback(WatchAwareCallback)
            .build()
        playerController.player.addListener(widgetListener)
        refreshWidget()
    }

    /**
     * Accepts every controller (phone UI, Wear OS / Samsung watch, headset, car) with all player commands,
     * and logs who connects and what they ask for (logcat tag "YoutublyWatch"), so a watch that "does
     * nothing" can be told apart from one that never connects.
     */
    private object WatchAwareCallback : MediaSession.Callback {
        private const val TAG = "YoutublyWatch"

        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            android.util.Log.i(TAG, "Controller connected: ${controller.packageName} (trusted=${controller.isTrusted})")
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session).build()
        }

        override fun onPlayerCommandRequest(session: MediaSession, controller: MediaSession.ControllerInfo, playerCommand: Int): Int {
            android.util.Log.i(TAG, "Command $playerCommand from ${controller.packageName}")
            return androidx.media3.session.SessionResult.RESULT_SUCCESS
        }

        override fun onMediaButtonEvent(session: MediaSession, controllerInfo: MediaSession.ControllerInfo, intent: Intent): Boolean {
            android.util.Log.i(TAG, "Media button from ${controllerInfo.packageName}: ${intent.action}")
            return false   // default handling: play/pause/next/previous/seek on the session player
        }

        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            android.util.Log.i(TAG, "Controller disconnected: ${controller.packageName}")
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /**
     * The task can be removed (app swiped away) mid-buffer, when isPlaying is
     * momentarily false even though playback is meant to continue. playWhenReady
     * is the durable signal of user intent — only stop when that's also false.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || player.mediaItemCount == 0 || !player.playWhenReady) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        // Player is owned by the singleton PlayerController; only release the session here.
        playerController.player.removeListener(widgetListener)
        mediaSession?.release()
        mediaSession = null
        // Final redraw so the widget drops "Now playing"; runs on a scope that outlives us.
        val app = applicationContext
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            com.youtubly.app.widget.YoutublyWidget.refresh(app)
        }
        widgetScope.cancel()
        super.onDestroy()
    }
}
