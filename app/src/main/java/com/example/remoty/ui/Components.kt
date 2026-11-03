package com.example.remoty.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Brand.Card)
            .border(1.dp, Brand.CardBorder, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

@Composable
fun GradientButton(text: String, brush: Brush, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) brush else Brush.linearGradient(listOf(Brand.TrackDark, Brand.TrackDark)))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) Color.White else Brand.TextSecondary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

fun statusColor(status: String): Color = when (status) {
    "succeeded" -> Brand.StatusGreen
    "failed" -> Brand.StatusRed
    "running" -> Brand.StatusBlue
    "stopped" -> Brand.TextSecondary
    else -> Brand.StatusAmber
}

@Composable
fun StatusPill(status: String) {
    val c = statusColor(status)
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = 0.16f)).border(1.dp, c.copy(alpha = 0.6f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 3.dp)
    ) { Text(status, color = c, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun Dot(color: Color) {
    Box(Modifier.padding(end = 2.dp).size(10.dp).clip(CircleShape).background(color))
}

@Composable
fun Hint(text: String) = Text(text, color = Brand.TextSecondary, fontSize = 13.sp)
