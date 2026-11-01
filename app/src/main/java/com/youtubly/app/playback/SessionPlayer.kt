package com.youtubly.app.playback

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import java.util.concurrent.CopyOnWriteArrayList

/** Next/previous for a queue the app manages itself (one item loaded in ExoPlayer at a time). */
interface QueueNavigator {
    fun hasNext(): Boolean
    fun hasPrevious(): Boolean
    fun next()
    fun previous()
}

/**
 * The player the MediaSession sees. Same as the app's ExoPlayer, except that while a
 * [QueueNavigator] is set it also offers next/previous — so the notification, lock screen,
 * headset and car buttons can skip through downloads even though ExoPlayer only holds one file.
 */
@OptIn(UnstableApi::class)
class SessionPlayer(player: Player) : ForwardingPlayer(player) {
    var navigator: QueueNavigator? = null
        set(value) { field = value; notifyCommandsChanged() }

    private val listeners = CopyOnWriteArrayList<Player.Listener>()

    override fun addListener(listener: Player.Listener) { super.addListener(listener); listeners += listener }
    override fun removeListener(listener: Player.Listener) { super.removeListener(listener); listeners -= listener }

    /** Call when the queue position changes so the notification shows/hides its skip buttons. */
    fun notifyCommandsChanged() {
        val cmds = availableCommands
        listeners.forEach { it.onAvailableCommandsChanged(cmds) }
    }

    private val skipCommands = intArrayOf(
        COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
    )

    /**
     * One video and no queue: there is nothing to skip to. Left alone, ⏮ on a band / headset / car just restarts
     * the video from 0 (ExoPlayer's "previous" when past 3 s) and ⏭ is not offered at all, so a Galaxy Fit
     * could only ever "go back to the beginning". Here ⏮ / ⏭ become −/+ the seek step (10 s) instead.
     */
    private fun isSingleItem() = navigator == null && mediaItemCount <= 1

    override fun getAvailableCommands(): Player.Commands {
        val nav = navigator
        if (nav == null) {
            val base = super.getAvailableCommands()
            if (!isSingleItem()) return base
            return base.buildUpon().addAll(*skipCommands).build()
        }
        val b = super.getAvailableCommands().buildUpon()
        b.removeAll(*skipCommands)
        if (nav.hasNext()) b.addAll(COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        // "Previous" is always offered: restarts the item, or goes back when near its start.
        b.addAll(COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        return b.build()
    }

    override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

    override fun hasNextMediaItem(): Boolean = navigator?.hasNext() ?: super.hasNextMediaItem()
    override fun hasPreviousMediaItem(): Boolean = navigator?.hasPrevious() ?: super.hasPreviousMediaItem()

    override fun seekToNext() {
        val nav = navigator
        when {
            nav != null && nav.hasNext() -> nav.next()
            isSingleItem() -> seekForward()
            else -> super.seekToNext()
        }
    }
    override fun seekToNextMediaItem() {
        val nav = navigator
        when {
            nav != null && nav.hasNext() -> nav.next()
            isSingleItem() -> seekForward()
            else -> super.seekToNextMediaItem()
        }
    }

    override fun seekToPrevious() {
        val nav = navigator ?: return if (isSingleItem()) seekBack() else super.seekToPrevious()
        if (currentPosition > RESTART_THRESHOLD_MS || !nav.hasPrevious()) seekTo(0) else nav.previous()
    }
    override fun seekToPreviousMediaItem() {
        val nav = navigator ?: return if (isSingleItem()) seekBack() else super.seekToPreviousMediaItem()
        if (nav.hasPrevious()) nav.previous() else seekTo(0)
    }

    private companion object { const val RESTART_THRESHOLD_MS = 3_000L }
}
