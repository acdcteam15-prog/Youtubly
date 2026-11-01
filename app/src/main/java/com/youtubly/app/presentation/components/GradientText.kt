package com.youtubly.app.presentation.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import com.youtubly.app.presentation.theme.BrandGradientH

@Composable
fun GradientTitle(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.displaySmall) {
    Text(text, modifier = modifier, style = style.copy(brush = BrandGradientH))
}
