package com.youtubly.app.widget

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.media3.common.Player
import com.youtubly.app.MainActivity
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Quick Settings tile.
 *  - Something is loaded in the player: the tile shows its title and toggles play/pause.
 *  - Nothing loaded: tapping opens "Continue watching" (the same deep link as the launcher shortcut).
 * Also available on the lock screen's quick settings; a secure lock asks to unlock first.
 */
class YoutublyTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartListening() = refreshTile()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        if (isLocked && isSecure) unlockAndRun { act() } else act()
    }

    private fun controller() =
        EntryPointAccessors.fromApplication(applicationContext, WidgetEntryPoint::class.java).playerController()

    private fun loaded(): Boolean {
        val pc = controller()
        // currentVideoId first: avoids creating the (lazy) ExoPlayer when nothing was ever played.
        return pc.currentVideoId != null && pc.player.mediaItemCount > 0
    }

    private fun act() {
        if (loaded()) {
            scope.launch {
                WidgetData.togglePlayPause(applicationContext)
                kotlinx.coroutines.delay(400)
                refreshTile()
            }
        } else {
            openContinue()
        }
    }

    private fun openContinue() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("youtubly://continue"))
            .setClass(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        if (loaded()) {
            val p = controller().player
            val playing = p.playWhenReady && p.playbackState != Player.STATE_ENDED && p.playbackState != Player.STATE_IDLE
            tile.state = if (playing) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.label = p.mediaMetadata.title?.toString() ?: "Youtubly"
            if (Build.VERSION.SDK_INT >= 29) tile.subtitle = if (playing) "Tap to pause" else "Tap to play"
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "Youtubly"
            if (Build.VERSION.SDK_INT >= 29) tile.subtitle = "Continue watching"
        }
        tile.updateTile()
    }

    companion object {
        /** Ask the system to re-bind and redraw the tile (playback state changed). */
        fun requestRefresh(context: Context) {
            runCatching { requestListeningState(context, ComponentName(context, YoutublyTileService::class.java)) }
        }
    }
}
