package com.youtubly.app.presentation.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val DarkColors = darkColorScheme(
    primary = Ember,
    onPrimary = Mist,
    primaryContainer = Color0(0xFF3A0F18),
    onPrimaryContainer = Color0(0xFFFFD9DE),
    secondary = Violet,
    onSecondary = Mist,
    secondaryContainer = Color0(0xFF2A1F52),
    onSecondaryContainer = Color0(0xFFE6DEFF),
    tertiary = Cyan,
    background = Ink0,
    onBackground = Mist,
    surface = Ink1,
    onSurface = Mist,
    surfaceVariant = Ink3,
    onSurfaceVariant = MistDim,
    surfaceContainerLowest = Ink0,
    surfaceContainerLow = Ink1,
    surfaceContainer = Ink2,
    surfaceContainerHigh = Ink3,
    surfaceContainerHighest = Ink4,
    outline = Ink4,
    outlineVariant = Ink3
)

private val LightColors = lightColorScheme(
    primary = Ember,
    onPrimary = Paper0,
    secondary = Violet,
    onSecondary = Paper0,
    tertiary = Cyan,
    background = Paper0,
    onBackground = InkText,
    surface = Paper0,
    onSurface = InkText,
    surfaceVariant = Paper2,
    onSurfaceVariant = InkTextDim,
    surfaceContainerLowest = Paper0,
    surfaceContainerLow = Paper1,
    surfaceContainer = Paper1,
    surfaceContainerHigh = Paper2,
    surfaceContainerHighest = Paper2,
    outline = Paper2,
    outlineVariant = Paper1
)

@Suppress("FunctionName")
private fun Color0(v: Long) = androidx.compose.ui.graphics.Color(v)

val YoutublyShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

enum class ThemeMode(val label: String) {
    SYSTEM("System"), LIGHT("Light"), DARK("Dark"), BLACK("Black");

    companion object {
        fun from(name: String?): ThemeMode = entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

/** Readable text on a coloured fill: dark text on bright colours (Cyan, Amber, Mint…). */
private fun onColorFor(c: Color, dark: Boolean): Color =
    if (c.luminance() > 0.45f) InkText else if (dark) Mist else Paper0

private fun ColorScheme.withAccent(accent: Accent, dark: Boolean): ColorScheme {
    val p = accent.color
    val sec = accent.secondary ?: secondary
    val ter = accent.tertiary ?: tertiary
    // Containers are derived from the accent so chips/cards match it (they used to stay
    // Ember-red whatever accent was picked).
    val containerMix = if (dark) 0.72f else 0.82f
    return copy(
        primary = p,
        onPrimary = onColorFor(p, dark),
        primaryContainer = lerp(p, surface, containerMix),
        onPrimaryContainer = lerp(p, onSurface, if (dark) 0.35f else 0.55f),
        inversePrimary = lerp(p, if (dark) Color.Black else Color.White, 0.35f),
        secondary = sec,
        onSecondary = onColorFor(sec, dark),
        secondaryContainer = lerp(sec, surface, containerMix),
        onSecondaryContainer = lerp(sec, onSurface, if (dark) 0.35f else 0.55f),
        tertiary = ter,
        onTertiary = onColorFor(ter, dark),
        tertiaryContainer = lerp(ter, surface, containerMix),
        onTertiaryContainer = lerp(ter, onSurface, if (dark) 0.35f else 0.55f),
        surfaceTint = p
    )
}

/**
 * "Tinted surfaces": nudges backgrounds and containers toward the accent so the whole app
 * takes on the palette. In Black mode the background stays pure black (AMOLED) and only
 * raised containers pick up the tint.
 */
private fun ColorScheme.withTintedSurfaces(tint: Color, keepBlackBackground: Boolean): ColorScheme {
    fun t(c: Color, amount: Float) = lerp(c, tint, amount)
    return copy(
        background = if (keepBlackBackground) background else t(background, 0.04f),
        surface = if (keepBlackBackground) surface else t(surface, 0.05f),
        surfaceVariant = t(surfaceVariant, 0.08f),
        surfaceContainerLowest = if (keepBlackBackground) surfaceContainerLowest else t(surfaceContainerLowest, 0.04f),
        surfaceContainerLow = if (keepBlackBackground) surfaceContainerLow else t(surfaceContainerLow, 0.05f),
        surfaceContainer = t(surfaceContainer, 0.07f),
        surfaceContainerHigh = t(surfaceContainerHigh, 0.09f),
        surfaceContainerHighest = t(surfaceContainerHighest, 0.11f),
        outlineVariant = t(outlineVariant, 0.10f)
    )
}

private fun ColorScheme.withBlackSurfaces(): ColorScheme = copy(
    background = Black0,
    surface = Black0,
    surfaceVariant = Black2,
    surfaceContainerLowest = Black0,
    surfaceContainerLow = Black0,
    surfaceContainer = Black1,
    surfaceContainerHigh = Black2,
    surfaceContainerHighest = Black3,
    outline = Black3,
    outlineVariant = Black2,
    surfaceTint = Black0
)

private val BlackColors = DarkColors.copy(
    background = Black0,
    surface = Black0,
    surfaceVariant = Black2,
    surfaceContainerLowest = Black0,
    surfaceContainerLow = Black0,
    surfaceContainer = Black1,
    surfaceContainerHigh = Black2,
    surfaceContainerHighest = Black3,
    outline = Black3,
    outlineVariant = Black2,
    surfaceTint = Black0
)

/**
 * App theme. [mode] System/Light/Dark/Black; [accent] index into [Accents];
 * [tintedSurfaces] tints backgrounds toward the accent. Changes cross-fade over ~350 ms.
 */
@Composable
fun YoutublyTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    accent: Int = 0,
    dynamicColor: Boolean = false,
    tintedSurfaces: Boolean = false,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.BLACK -> true
    }
    val black = mode == ThemeMode.BLACK
    val useDynamic = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val target: ColorScheme = if (useDynamic) {
        // Material You: wallpaper colours drive primary/secondary/etc. The accent setting is
        // ignored. Black mode keeps its pure-black surfaces on top of the dynamic palette.
        val ctx = LocalContext.current
        val dyn = if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        val base = if (black) dyn.withBlackSurfaces() else dyn
        if (tintedSurfaces) base.withTintedSurfaces(base.primary, keepBlackBackground = black) else base
    } else {
        val base = when {
            black -> BlackColors
            darkTheme -> DarkColors
            else -> LightColors
        }
        val a = Accents.getOrNull(accent) ?: Accents.first()
        // Black keeps a black surfaceTint so tonal elevation doesn't lift sheets off pure black.
        val accented = base.withAccent(a, darkTheme).let { if (black) it.copy(surfaceTint = Black0) else it }
        if (tintedSurfaces) accented.withTintedSurfaces(a.color, keepBlackBackground = black) else accented
    }
    val scheme = target.animated()

    // Status/nav bar icons must contrast with the app's own theme, not whatever the
    // platform guessed at enableEdgeToEdge() time (some OEMs get this wrong, leaving the
    // clock/battery icons dark-on-dark or light-on-light).
    val view = LocalView.current
    if (!view.isInEditMode) {
        // Keyed on darkTheme only, so it doesn't stomp on a screen (e.g. the player)
        // that temporarily overrides the icon colour.
        DisposableEffect(darkTheme) {
            (view.context as? android.app.Activity)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            onDispose { }
        }
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = YoutublyTypography,
        shapes = YoutublyShapes,
        content = content
    )
}
