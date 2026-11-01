package com.youtubly.app.presentation.components

import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NotInterested
import androidx.compose.material.icons.rounded.PersonOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * True when the user turned animations off (Settings → Accessibility → Remove animations, or
 * developer "Animator duration scale" = off). Looping / decorative motion checks this.
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val ctx = LocalContext.current
    return remember {
        runCatching {
            Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}

/**
 * A quick springy "pop" (scale 1 → 1.25 → 1) whenever [key] changes — never on first show.
 * Used for Like, Subscribe, and small status badges.
 */
fun Modifier.popOnChange(key: Any?, peak: Float = 1.25f): Modifier = composed {
    val scale = remember { Animatable(1f) }
    val reduce = rememberReduceMotion()
    var first by remember { androidx.compose.runtime.mutableStateOf(true) }
    LaunchedEffect(key) {
        if (first) { first = false; return@LaunchedEffect }
        if (reduce) return@LaunchedEffect
        scale.snapTo(0.85f)
        scale.animateTo(peak, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium))
        scale.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow))
    }
    graphicsLayer { scaleX = scale.value; scaleY = scale.value }
}

/**
 * Swipe a video card sideways for quick feedback, like a mail app:
 * swipe **right** → "Not interested"; swipe **left** → "Don't recommend channel".
 * The card's own "removed · Undo" row takes over once an action fires.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeVideoActions(
    onNotInterested: (() -> Unit)?,
    onDontRecommendChannel: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val cfg = LocalSwipeSettings.current.value
    val onRight = onNotInterested.takeIf { cfg.right }
    val onLeft = onDontRecommendChannel.takeIf { cfg.left }
    if (onRight == null && onLeft == null) {
        Box(modifier) { content() }
        return
    }
    val haptics = LocalHapticFeedback.current
    val right by rememberUpdatedState(onRight)
    val left by rememberUpdatedState(onLeft)
    val fraction by rememberUpdatedState(cfg.distancePct / 100f)
    var widthPx by remember { mutableFloatStateOf(0f) }
    val stateRef = remember { arrayOfNulls<SwipeToDismissBoxState>(1) }
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { v ->
            // SwipeToDismissBox also accepts a quick sideways flick (low built-in velocity
            // threshold) — that's what fired while scrolling diagonally. Only a drag that
            // really travelled the configured distance counts.
            val travelled = runCatching { kotlin.math.abs(stateRef[0]!!.requireOffset()) }.getOrDefault(0f)
            val farEnough = widthPx > 0f && travelled >= widthPx * fraction * 0.98f
            when (v) {
                SwipeToDismissBoxValue.StartToEnd -> if (!farEnough) false else right?.let { haptics.performHapticFeedback(HapticFeedbackType.LongPress); it(); true } ?: false
                SwipeToDismissBoxValue.EndToStart -> if (!farEnough) false else left?.let { haptics.performHapticFeedback(HapticFeedbackType.LongPress); it(); true } ?: false
                SwipeToDismissBoxValue.Settled -> true
            }
        },
        positionalThreshold = { total -> total * fraction }
    )
    stateRef[0] = state
    // A light tick the moment the swipe crosses the threshold (before release).
    LaunchedEffect(state.targetValue) {
        if (state.targetValue != SwipeToDismissBoxValue.Settled) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    SwipeToDismissBox(
        state = state,
        modifier = modifier.onSizeChanged { widthPx = it.width.toFloat() },
        enableDismissFromStartToEnd = onRight != null,
        enableDismissFromEndToStart = onLeft != null,
        backgroundContent = {
            val dir = state.dismissDirection
            val armed = state.targetValue != SwipeToDismissBoxValue.Settled
            val iconScale by animateFloatAsState(if (armed) 1.2f else 0.9f, spring(dampingRatio = 0.5f), label = "swipeIcon")
            val toRight = dir == SwipeToDismissBoxValue.StartToEnd
            val bg = when {
                dir == SwipeToDismissBoxValue.Settled -> androidx.compose.ui.graphics.Color.Transparent
                toRight -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.errorContainer
            }
            val fg = if (toRight) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer
            Box(
                Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp).clip(MaterialTheme.shapes.large).background(bg).padding(horizontal = 24.dp),
                contentAlignment = if (toRight) Alignment.CenterStart else Alignment.CenterEnd
            ) {
                if (dir != SwipeToDismissBoxValue.Settled) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (!toRight) Label(if (armed) "Release: don't recommend channel" else "Don't recommend channel", fg)
                        Icon(
                            if (toRight) Icons.Rounded.NotInterested else Icons.Rounded.PersonOff, null, tint = fg,
                            modifier = Modifier.size(28.dp).graphicsLayer { scaleX = iconScale; scaleY = iconScale }
                        )
                        if (toRight) Label(if (armed) "Release: not interested" else "Not interested", fg)
                    }
                }
            }
        }
    ) { content() }
}

@Composable
private fun Label(text: String, color: androidx.compose.ui.graphics.Color) {
    Column {
        Text(text, color = color, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
    Spacer(Modifier.width(0.dp))
}
