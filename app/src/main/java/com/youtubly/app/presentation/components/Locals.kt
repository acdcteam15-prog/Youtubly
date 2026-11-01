package com.youtubly.app.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.youtubly.app.data.repository.Branding
import com.youtubly.app.data.repository.DeArrowRepository

/** Long-press actions (Not interested, Don't recommend…). Provided by MainActivity. */
val LocalVideoActions = staticCompositionLocalOf<com.youtubly.app.playback.VideoActions?> { null }

/** Opens a channel from anywhere (long-press → Go to channel). Provided by the nav host. */
val LocalOpenChannel = staticCompositionLocalOf<((String) -> Unit)?> { null }

/** DeArrow lookups (null in previews). Provided by MainActivity. */
val LocalDeArrow = staticCompositionLocalOf<DeArrowRepository?> { null }

/** videoId → fraction watched, for thumbnail progress bars. Provided by MainActivity. */
val LocalWatchProgress = staticCompositionLocalOf<State<Map<String, Float>>> { mutableStateOf(emptyMap()) }

/** Live connectivity state (true = online). Provided by MainActivity; starts true until the
 *  first real callback arrives, so nothing flashes an offline state on cold start. */
val LocalIsOnline = staticCompositionLocalOf<State<Boolean>> { mutableStateOf(true) }

/** True while data saver is active (setting on + mobile data). Provided by MainActivity. */
val LocalDataSaver = staticCompositionLocalOf<State<Boolean>> { mutableStateOf(false) }

/** Local playlists, for the "Save to playlist" sheet reachable from any video card or the player. */
val LocalPlaylists = staticCompositionLocalOf<com.youtubly.app.data.repository.PlaylistRepository?> { null }

/**
 * Phase 8: true on tablets / unfolded foldables (width and height both at least Medium).
 * Phones — portrait or landscape — are false, so their layout is unchanged.
 */
val LocalWideLayout = staticCompositionLocalOf<Boolean> { false }

/** Phase 8 setting: on a phone, turning to landscape opens fullscreen (and back exits). */
val LocalRotateToFullscreen = staticCompositionLocalOf<State<Boolean>> { mutableStateOf(false) }

/** Swipe-on-card settings (You → Settings → Swipe actions). */
data class SwipeSettings(val right: Boolean = true, val left: Boolean = true, val distancePct: Int = 55)
val LocalSwipeSettings = staticCompositionLocalOf<State<SwipeSettings>> { mutableStateOf(SwipeSettings()) }

/** Double-tap seek step in seconds (Phase 5 setting: 5, 10, 15 or 30). */
val LocalSeekStepSeconds = staticCompositionLocalOf<State<Int>> { mutableStateOf(10) }

/** DeArrow branding for a video, or null (disabled / nothing submitted / still loading). */
@Composable
fun rememberBranding(videoId: String): Branding? {
    val repo = LocalDeArrow.current ?: return null
    val enabled by repo.enabled.collectAsState()
    val noThumbs by repo.thumbnailsBlocked.collectAsState()
    if (!enabled) return null
    val branding by produceState(initialValue = repo.cached(videoId), videoId) {
        value = repo.branding(videoId)
    }
    return if (noThumbs) branding?.copy(thumbnailUrl = null) else branding
}

/** Thumbnail URL to load: the small "mqdefault" variant while data saver is active. */
@Composable
fun thumbnailFor(url: String?): String? =
    if (LocalDataSaver.current.value) com.youtubly.app.core.NetworkPolicy.smallThumbnail(url) else url

/**
 * Space to keep free at the bottom of scrolling lists while the player's mini bar is showing, so
 * the last item can be scrolled clear of it. Zero when nothing is playing. Provided by the nav host.
 * Add it to a list's bottom padding: `bottom = 120.dp + miniBarClearance()`.
 */
val LocalMiniBarClearance = staticCompositionLocalOf<State<Dp>> { mutableStateOf(0.dp) }

@Composable
fun miniBarClearance(): Dp = LocalMiniBarClearance.current.value
