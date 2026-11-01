package com.youtubly.app.playback

import androidx.compose.runtime.mutableStateOf

/**
 * Shared picture-in-picture state between MainActivity (which enters PiP)
 * and the player screen (which offers a manual trigger).
 */
object PipState {
    /** Compose-observable: the activity is currently shown as a PiP window. */
    val inPip = mutableStateOf(false)

    /**
     * Bumped to ask MainActivity to enter PiP right now (used by the manual PiP button,
     * since onUserLeaveHint is unreliable on some OEM gesture-nav / app-switcher setups).
     */
    val requestPip = mutableStateOf(0)
    fun requestNow() { requestPip.value++ }

    /** Nav route of the last opened *downloaded-file* player, so the floating mini player
     *  (kept only for downloads since Phase 7) can bring the user back to it. Streamed videos
     *  live in the player sheet and don't use this. */
    val lastPlayerRoute = mutableStateOf<String?>(null)
}
