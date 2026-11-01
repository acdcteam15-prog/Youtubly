package com.youtubly.app.presentation.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.youtubly.app.presentation.theme.accentDeep

/**
 * Soft, slowly drifting colour glow behind screen headers — gives the app its
 * "alive" feel without distracting from content.
 */
@Composable
fun AuroraBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val reduce = rememberReduceMotion()
    val t = rememberInfiniteTransition(label = "aurora")
    val driftAnim by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(9000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "drift"
    )
    val pulseAnim by t.animateFloat(
        0.85f, 1.15f,
        infiniteRepeatable(tween(5200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse"
    )
    // Animations off (system setting): keep the glow, but still.
    val drift = if (reduce) 0.5f else driftAnim
    val pulse = if (reduce) 1f else pulseAnim
    val bg = MaterialTheme.colorScheme.background
    val accent = MaterialTheme.colorScheme.primary
    val accent2 = accentDeep(accent)
    // Black (AMOLED) theme: no glow at all, so backgrounds stay pure black.
    val pureBlack = bg == Color.Black

    Box(modifier.fillMaxSize()) {
        if (!pureBlack) Canvas(Modifier.fillMaxWidth().height(420.dp)) {
            val w = size.width
            val h = size.height
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(accent.copy(alpha = 0.32f), Color.Transparent),
                    center = Offset(w * (0.15f + 0.35f * drift), h * 0.15f),
                    radius = w * 0.65f * pulse
                ),
                radius = w * 0.65f * pulse,
                center = Offset(w * (0.15f + 0.35f * drift), h * 0.15f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(accent2.copy(alpha = 0.40f), Color.Transparent),
                    center = Offset(w * (0.9f - 0.3f * drift), h * 0.35f),
                    radius = w * 0.6f / pulse
                ),
                radius = w * 0.6f / pulse,
                center = Offset(w * (0.9f - 0.3f * drift), h * 0.35f)
            )
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, bg), startY = h * 0.45f, endY = h))
        }
        content()
    }
}
