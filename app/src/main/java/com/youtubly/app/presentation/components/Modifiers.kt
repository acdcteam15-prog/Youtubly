package com.youtubly.app.presentation.components

import androidx.compose.foundation.combinedClickable

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Springy press-to-shrink click, the core "tactile" feel of the UI. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun Modifier.bounceClick(
    scaleDown: Float = 0.96f,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) scaleDown else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "bounce"
    )
    val haptics = LocalHapticFeedback.current
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .combinedClickable(
            interactionSource = interaction,
            indication = null,
            onLongClick = onLongClick?.let { lc -> { haptics.performHapticFeedback(HapticFeedbackType.LongPress); lc() } },
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
        )
}

/** Staggered fade + rise entrance for list items (index-based delay, capped). */
fun Modifier.staggeredEntrance(index: Int): Modifier = composed {
    val alpha = remember { Animatable(0f) }
    val offset = remember { Animatable(1f) }
    val rise = with(LocalDensity.current) { 28.dp.toPx() }
    val reduce = rememberReduceMotion()
    LaunchedEffect(Unit) {
        if (reduce) { alpha.snapTo(1f); offset.snapTo(0f); return@LaunchedEffect }
        delay((index.coerceAtMost(8) * 55L))
        launch { alpha.animateTo(1f, tween(420)) }
        offset.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessLow))
    }
    graphicsLayer {
        this.alpha = alpha.value
        translationY = offset.value * rise
    }
}
