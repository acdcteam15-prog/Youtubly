package com.youtubly.app.presentation.components

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.youtubly.app.playback.PlayerController
import kotlin.math.roundToInt

/** Snapshot of the singleton player, refreshed on every player event. */
private data class MiniSnapshot(val active: Boolean, val playing: Boolean, val title: String)

private fun PlayerController.snapshot() = MiniSnapshot(
    active = currentVideoId != null && player.mediaItemCount > 0,
    playing = player.isPlaying || (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING),
    title = player.mediaMetadata.title?.toString().orEmpty()
)

/**
 * In-app picture-in-picture: a draggable floating video window shown over the rest of the
 * app while something keeps playing after the user leaves the player screen.
 * Tap = back to the full player, ✕ = stop. When the whole activity is in *system* PiP while
 * the user was on another screen, it fills the PiP window so the video is still what shows.
 */
@OptIn(UnstableApi::class)
@Composable
fun MiniPlayer(
    controller: PlayerController,
    systemPip: Boolean,
    bottomInset: androidx.compose.ui.unit.Dp,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    var snap by remember { mutableStateOf(controller.snapshot()) }
    DisposableEffect(controller) {
        val l = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { snap = controller.snapshot() }
        }
        controller.player.addListener(l)
        snap = controller.snapshot()
        onDispose { controller.player.removeListener(l) }
    }
    if (!snap.active) return
    val audioOnly = controller.isAudioMode

    if (systemPip) {
        Box(modifier.fillMaxSize().background(Color.Black)) {
            if (!audioOnly) VideoSurface(controller, Modifier.fillMaxSize())
        }
        return
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val cardWidth = (maxWidth * 0.55f).coerceAtMost(280.dp)
        val cardHeight = cardWidth * 9f / 16f + 40.dp
        val margin = 12.dp
        val maxX = with(density) { (maxWidth - cardWidth - margin * 2).toPx() }.coerceAtLeast(0f)
        val maxY = with(density) { (maxHeight - cardHeight - margin * 2 - bottomInset).toPx() }.coerceAtLeast(0f)
        // Start bottom-right, above the nav bar. Stored as fractions so rotation keeps it sensible.
        var fx by rememberSaveable { mutableFloatStateOf(1f) }
        var fy by rememberSaveable { mutableFloatStateOf(1f) }
        // Horizontal drag past the screen edge = fling away to dismiss (like YouTube).
        var overshoot by remember { mutableFloatStateOf(0f) }
        val dismissPx = with(density) { 110.dp.toPx() }

        Column(
            Modifier
                .offset { IntOffset((fx * maxX + overshoot).roundToInt(), (fy * maxY).roundToInt()) }
                .graphicsLayer { alpha = 1f - (kotlin.math.abs(overshoot) / (dismissPx * 1.6f)).coerceIn(0f, 0.8f) }
                .padding(margin)
                .width(cardWidth)
                .shadow(16.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .pointerInput(maxX, maxY) {
                    detectDragGestures(
                        onDragEnd = {
                            if (kotlin.math.abs(overshoot) > dismissPx) {
                                controller.stop(); com.youtubly.app.playback.PipState.lastPlayerRoute.value = null
                            }
                            overshoot = 0f
                            fx = if (fx < 0.5f) 0f else 1f // snap to nearest side
                        },
                        onDragCancel = { overshoot = 0f }
                    ) { change, drag ->
                        change.consume()
                        if (maxX > 0) {
                            val nx = fx + drag.x / maxX
                            when {
                                nx < 0f -> { fx = 0f; overshoot += drag.x }
                                nx > 1f -> { fx = 1f; overshoot += drag.x }
                                overshoot != 0f -> overshoot = (overshoot + drag.x).let { if (it * overshoot <= 0f) 0f else it }
                                else -> fx = nx
                            }
                        }
                        if (maxY > 0) fy = (fy + drag.y / maxY).coerceIn(0f, 1f)
                    }
                }
                .clickable(onClick = onExpand)
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
                if (audioOnly) {
                    Icon(Icons.Rounded.GraphicEq, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(36.dp))
                } else {
                    VideoSurface(controller, Modifier.fillMaxSize())
                }
                MiniProgress(controller, snap.playing, Modifier.align(Alignment.BottomStart))
            }
            Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    snap.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                SmallIcon(if (snap.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/pause") {
                    val p = controller.player
                    if (p.isPlaying) p.pause() else { if (p.playbackState == Player.STATE_ENDED) p.seekTo(0); p.play() }
                }
                SmallIcon(Icons.Rounded.Close, "Close") { controller.stop(); com.youtubly.app.playback.PipState.lastPlayerRoute.value = null }
            }
        }
    }
}

/** 2dp accent progress line along the bottom of the video; polls twice a second while playing. */
@Composable
private fun MiniProgress(controller: PlayerController, playing: Boolean, modifier: Modifier) {
    var fraction by remember { mutableFloatStateOf(0f) }
    androidx.compose.runtime.LaunchedEffect(playing) {
        while (true) {
            val p = controller.player
            val d = p.duration
            fraction = if (d > 0) (p.currentPosition.toFloat() / d).coerceIn(0f, 1f) else 0f
            if (!playing) break
            kotlinx.coroutines.delay(500)
        }
    }
    Box(modifier.fillMaxWidth().height(2.dp).background(Color.White.copy(alpha = 0.25f))) {
        Box(Modifier.fillMaxWidth(fraction).height(2.dp).background(MaterialTheme.colorScheme.primary))
    }
}

@Composable
private fun SmallIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, desc, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp)) }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(controller: PlayerController, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            (android.view.LayoutInflater.from(ctx).inflate(com.youtubly.app.R.layout.mini_player_view, null) as PlayerView).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                player = controller.player
            }
        },
        onRelease = { it.player = null }
    )
}
