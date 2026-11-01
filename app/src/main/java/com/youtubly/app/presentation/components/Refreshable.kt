package com.youtubly.app.presentation.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * The app's one pull-to-refresh wrapper (Material3 [PullToRefreshBox]), so every screen
 * pulls, spins and colours the same way. [indicatorBelowStatusBar] = true for screens whose
 * content draws under the status bar (edge-to-edge headers).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Refreshable(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    indicatorBelowStatusBar: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    val state = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        state = state,
        indicator = {
            // Branded indicator: grows with the pull, the arc fills as you drag, and it spins
            // in the accent colour while refreshing.
            val pull = state.distanceFraction.coerceIn(0f, 1.5f)
            val spin = rememberInfiniteTransition(label = "ptr")
                .animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "ptrSpin")
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .then(if (indicatorBelowStatusBar) Modifier.statusBarsPadding() else Modifier)
                    .graphicsLayer {
                        val shown = if (refreshing) 1f else pull.coerceAtMost(1f)
                        translationY = (if (refreshing) 1f else pull) * 64.dp.toPx() - 40.dp.toPx()
                        scaleX = 0.6f + 0.4f * shown; scaleY = scaleX
                        alpha = shown
                        rotationZ = if (refreshing) spin.value else pull * 270f
                    }
                    .size(40.dp)
                    .shadow(4.dp, CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (refreshing) CircularProgressIndicator(
                    modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp, color = MaterialTheme.colorScheme.primary
                ) else CircularProgressIndicator(
                    progress = { pull.coerceAtMost(1f) * 0.85f },
                    modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp, color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.Transparent
                )
            }
        },
        content = content
    )
}
