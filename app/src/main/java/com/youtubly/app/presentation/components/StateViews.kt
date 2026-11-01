package com.youtubly.app.presentation.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.youtubly.app.presentation.theme.BrandGradient

@Composable
fun ErrorView(message: String, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    EmptyState(
        icon = Icons.Rounded.CloudOff,
        title = "Something went sideways",
        subtitle = message,
        actionLabel = if (onRetry != null) "Try again" else null,
        onAction = onRetry,
        modifier = modifier
    )
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    val t = rememberInfiniteTransition(label = "float")
    val reduce = rememberReduceMotion()
    val bobAnim by t.animateFloat(-6f, 6f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "bob")
    val bob = if (reduce) 0f else bobAnim
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Illustration: soft concentric rings + a few orbiting dots around the icon badge.
        val ring = MaterialTheme.colorScheme.primary
        val orbitAnim by t.animateFloat(0f, 360f, infiniteRepeatable(tween(14_000, easing = androidx.compose.animation.core.LinearEasing)), label = "orbit")
        val orbit = if (reduce) 30f else orbitAnim
        Box(Modifier.size(168.dp), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
                val c = center
                val r = size.minDimension / 2
                drawCircle(ring.copy(alpha = 0.06f), radius = r, center = c)
                drawCircle(ring.copy(alpha = 0.10f), radius = r * 0.74f, center = c)
                drawCircle(ring.copy(alpha = 0.18f), radius = r * 0.74f, center = c,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()))
                listOf(0f, 130f, 250f).forEachIndexed { i, base ->
                    val a = Math.toRadians((base + orbit).toDouble())
                    val rr = r * (0.88f - i * 0.07f)
                    drawCircle(ring.copy(alpha = 0.45f - i * 0.1f), radius = (5 - i).dp.toPx(),
                        center = androidx.compose.ui.geometry.Offset(c.x + rr * kotlin.math.cos(a).toFloat(), c.y + rr * kotlin.math.sin(a).toFloat()))
                }
            }
            Box(
                Modifier
                    .graphicsLayer { translationY = bob }
                    .size(84.dp)
                    .clip(CircleShape)
                    .background(BrandGradient),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(38.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 4
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            FilledTonalButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}
