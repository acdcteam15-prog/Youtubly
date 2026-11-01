package com.youtubly.app.presentation.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.sp
import com.youtubly.app.R

/**
 * Cairo: a modern geometric typeface with a proper Arabic design (the Latin letters match it), so Arabic titles
 * no longer fall back to a different system font. Bundled (subset to Arabic + Latin), so it also works offline.
 */
private val Display = FontFamily(
    Font(R.font.cairo_regular, FontWeight.Normal),
    Font(R.font.cairo_medium, FontWeight.Medium),
    Font(R.font.cairo_semibold, FontWeight.SemiBold),
    Font(R.font.cairo_bold, FontWeight.Bold),
    Font(R.font.cairo_extrabold, FontWeight.ExtraBold),
    Font(R.font.cairo_black, FontWeight.Black)
)

/**
 * Two rules that matter for Arabic:
 *  - letterSpacing stays 0: any other value breaks the joined letters apart on Android.
 *  - the text direction follows the text itself, so an Arabic title lays out right-to-left (punctuation lands on the
 *    correct side) while English text is unchanged.
 */
private fun style(weight: FontWeight, size: Int, lineHeight: Int? = null) = TextStyle(
    fontFamily = Display,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = (lineHeight ?: (size * 1.4f).toInt()).sp,
    letterSpacing = 0.sp,
    textDirection = TextDirection.Content
)

val YoutublyTypography = Typography(
    displaySmall = style(FontWeight.Black, 34, 40),
    headlineMedium = style(FontWeight.ExtraBold, 26, 34),
    headlineSmall = style(FontWeight.Bold, 22, 30),
    titleLarge = style(FontWeight.Bold, 20, 28),
    titleMedium = style(FontWeight.SemiBold, 15, 22),
    titleSmall = style(FontWeight.SemiBold, 13, 19),
    bodyLarge = style(FontWeight.Normal, 15, 23),
    bodyMedium = style(FontWeight.Normal, 14, 21),
    bodySmall = style(FontWeight.Medium, 12, 18),
    labelLarge = style(FontWeight.SemiBold, 14, 20),
    labelMedium = style(FontWeight.Medium, 12, 16),
    labelSmall = style(FontWeight.Bold, 10, 14)
)
