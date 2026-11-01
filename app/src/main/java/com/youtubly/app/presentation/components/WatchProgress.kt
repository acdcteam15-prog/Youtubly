package com.youtubly.app.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Fraction at which a video counts as finished (matches History / Downloads). */
const val WATCHED_FRACTION = 0.95f

/** Watched fraction for [videoId] from the app-wide progress map (0 when never opened). */
@Composable
fun watchProgressOf(videoId: String): Float = LocalWatchProgress.current.value[videoId] ?: 0f

/**
 * Thumbnail overlay: a progress bar along the bottom edge and, once the video is finished,
 * a small "Watched" tag in the top-left corner. Call inside the thumbnail's Box.
 */
@Composable
fun BoxScope.WatchProgressOverlay(progress: Float, barHeight: Dp = 3.dp, showWatchedTag: Boolean = true) {
    if (progress <= 0.01f) return
    Box(
        Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .height(barHeight)
            .background(Color.White.copy(alpha = 0.3f))
    ) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(barHeight).background(MaterialTheme.colorScheme.primary))
    }
    // Already-finished videos show the tag at once; one that crosses 95% now pops it in.
    val watched = showWatchedTag && progress >= WATCHED_FRACTION
    val tagState = androidx.compose.runtime.remember { androidx.compose.animation.core.MutableTransitionState(watched) }
    tagState.targetState = watched
    androidx.compose.animation.AnimatedVisibility(
        visibleState = tagState,
        enter = androidx.compose.animation.scaleIn(androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = 600f), initialScale = 0.3f) +
            androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.fadeOut(),
        modifier = Modifier.align(Alignment.TopStart)
    ) {
        Row(
            Modifier
                .padding(6.dp)
                .clip(MaterialTheme.shapes.small)
                .background(Color.Black.copy(alpha = 0.72f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.CheckCircle, null, tint = Color.White, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
            Text("Watched", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}
