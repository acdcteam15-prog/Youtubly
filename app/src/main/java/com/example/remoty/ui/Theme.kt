package com.example.remoty.ui

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/** One complete set of colours. [gradA] is the main button gradient, [gradB] the secondary one. */
data class Palette(
    val key: String, val name: String, val light: Boolean,
    val bg: Color, val card: Color, val border: Color, val header: Color, val track: Color,
    val text: Color, val textDim: Color, val accent: Color,
    val gradA1: Color, val gradA2: Color, val gradB1: Color, val gradB2: Color,
)

object Palettes {
    val all = listOf(
        Palette("midnight", "Midnight", false, Color(0xFF070B14), Color(0xFF0F1628), Color(0xFF1E2D50), Color(0xFF0D1224), Color(0xFF1A2540),
            Color(0xFFF0F4FF), Color(0xFF8892B0), Color(0xFF4F8EF7), Color(0xFF4F8EF7), Color(0xFF00E676), Color(0xFF5E35B1), Color(0xFFAB47BC)),
        Palette("crimson", "Crimson", false, Color(0xFF0B0708), Color(0xFF1A1012), Color(0xFF3A1D22), Color(0xFF140C0E), Color(0xFF2A1A1D),
            Color(0xFFFFF1F1), Color(0xFFB59A9C), Color(0xFFFF5252), Color(0xFFE53935), Color(0xFFFF6F00), Color(0xFFB71C1C), Color(0xFFFF5252)),
        Palette("emerald", "Emerald", false, Color(0xFF06100C), Color(0xFF0D1B15), Color(0xFF1B3A2C), Color(0xFF0A1712), Color(0xFF15281F),
            Color(0xFFEFFAF4), Color(0xFF86A596), Color(0xFF2ECC71), Color(0xFF00B686), Color(0xFF2ECC71), Color(0xFF00897B), Color(0xFF26C6DA)),
        Palette("violet", "Violet", false, Color(0xFF0C0814), Color(0xFF160F26), Color(0xFF2E2150), Color(0xFF110B1E), Color(0xFF211736),
            Color(0xFFF5F0FF), Color(0xFF9A8FB8), Color(0xFFB388FF), Color(0xFF7C4DFF), Color(0xFFB388FF), Color(0xFF5E35B1), Color(0xFFAB47BC)),
        Palette("amoled", "AMOLED black", false, Color(0xFF000000), Color(0xFF0B0B0B), Color(0xFF262626), Color(0xFF000000), Color(0xFF1A1A1A),
            Color(0xFFF2F2F2), Color(0xFF8C8C8C), Color(0xFF4F8EF7), Color(0xFF4F8EF7), Color(0xFF00E676), Color(0xFF5E35B1), Color(0xFFAB47BC)),
        Palette("light", "Light", true, Color(0xFFF5F7FC), Color(0xFFFFFFFF), Color(0xFFC7CFE4), Color(0xFFE9EDF7), Color(0xFFE1E6F2),
            Color(0xFF111827), Color(0xFF4A5578), Color(0xFF2F6FE0), Color(0xFF2F6FE0), Color(0xFF00A86B), Color(0xFF5E35B1), Color(0xFFAB47BC)),
    )

    fun byKey(key: String): Palette = all.firstOrNull { it.key == key } ?: all[0]
}

/** Every colour in the app comes from here; the values follow the chosen palette (Compose state, so screens redraw at once). */
object Brand {
    var palette by mutableStateOf(Palettes.all[0])

    val BgDeep: Color get() = palette.bg
    val Card: Color get() = palette.card
    val CardBorder: Color get() = palette.border
    val Header: Color get() = palette.header
    val AccentBlue: Color get() = palette.accent
    val TextPrimary: Color get() = palette.text
    val TextSecondary: Color get() = palette.textDim
    val TrackDark: Color get() = palette.track

    // status colours keep their meaning in every theme
    val StatusGreen = Color(0xFF00E676)
    val StatusRed = Color(0xFFFF5252)
    val StatusAmber = Color(0xFFFFB300)
    val StatusBlue = Color(0xFF448AFF)

    val BuildBrush: Brush get() = Brush.linearGradient(listOf(palette.gradB1, palette.gradB2))
    val RunBrush: Brush get() = Brush.linearGradient(listOf(Color(0xFF00897B), Color(0xFF26C6DA)))
    val UsageBrush: Brush get() = Brush.horizontalGradient(listOf(palette.gradA1, palette.gradA2))
    val DangerBrush: Brush get() = Brush.linearGradient(listOf(Color(0xFFE53935), Color(0xFFFF6F00)))
}

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
)

@Composable
fun RemotyTheme(content: @Composable () -> Unit) {
    val p = Brand.palette
    val scheme = if (p.light) lightColorScheme(
        primary = p.accent, onPrimary = Color.White, secondary = p.accent,
        background = p.bg, onBackground = p.text, surface = p.card, onSurface = p.text,
        surfaceVariant = p.track, onSurfaceVariant = p.textDim, outline = p.border, error = Brand.StatusRed,
    ) else darkColorScheme(
        primary = p.accent, onPrimary = Color.White, secondary = p.accent,
        background = p.bg, onBackground = p.text, surface = p.card, onSurface = p.text,
        surfaceVariant = p.track, onSurfaceVariant = p.textDim, outline = p.border, error = Brand.StatusRed,
    )
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val w = (view.context as? Activity)?.window ?: return@SideEffect
            w.statusBarColor = p.header.toArgb()
            w.navigationBarColor = p.bg.toArgb()
            w.setBackgroundDrawable(ColorDrawable(p.bg.toArgb()))
            WindowCompat.getInsetsController(w, view).apply {
                isAppearanceLightStatusBars = p.light
                isAppearanceLightNavigationBars = p.light
            }
        }
    }
    MaterialTheme(colorScheme = scheme, shapes = AppShapes) {
        // The surface gives every screen a background AND the right text colour. Without it, screens outside a Scaffold
        // (folder picker, job screen, editor, first screen) fell back to black text on the dark window.
        Surface(modifier = Modifier.fillMaxSize(), color = p.bg, contentColor = p.text, content = content)
    }
}
