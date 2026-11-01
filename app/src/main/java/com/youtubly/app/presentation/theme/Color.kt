package com.youtubly.app.presentation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

// Brand
val Ember = Color(0xFFFF2E4D)
val Violet = Color(0xFF7C4DFF)
val Cyan = Color(0xFF22D3EE)
val Amber = Color(0xFFFFB547)

// Dark surfaces (layered, slightly cool)
val Ink0 = Color(0xFF07070B)
val Ink1 = Color(0xFF0E0E15)
val Ink2 = Color(0xFF16161F)
val Ink3 = Color(0xFF20202C)
val Ink4 = Color(0xFF2B2B3A)
val Mist = Color(0xFFEDEDF5)
val MistDim = Color(0xFFB3B3C7)

// Light surfaces
val Paper0 = Color(0xFFFAFAFD)
val Paper1 = Color(0xFFF1F1F7)
val Paper2 = Color(0xFFE6E6EF)
val InkText = Color(0xFF12121A)
val InkTextDim = Color(0xFF5C5C70)

/** A darker shade of [c], used as the second stop of the brand gradients. */
fun accentDeep(c: Color): Color = lerp(c, Color.Black, 0.45f)

/**
 * Brand gradients follow the current accent (or wallpaper colour with Material You):
 * primary → a darker shade of primary. Composable getters so existing call sites
 * (`.background(BrandGradient)`) keep compiling and recolour on accent change.
 */
val BrandGradient: Brush
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.primary.let { Brush.linearGradient(listOf(it, accentDeep(it))) }

val BrandGradientH: Brush
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.primary.let { Brush.horizontalGradient(listOf(it, accentDeep(it))) }

/**
 * Colour choices for the Appearance setting; index 0 (Ember) is the brand default.
 * The first six are single accents (secondary/tertiary keep the app defaults). The rest are
 * full palettes that also set secondary and tertiary. The saved setting is an index, so new
 * entries are only ever appended — never reorder this list.
 */
data class Accent(
    val name: String,
    val color: Color,
    val secondary: Color? = null,
    val tertiary: Color? = null
)

val Accents = listOf(
    Accent("Ember", Ember),
    Accent("Violet", Violet),
    Accent("Cyan", Cyan),
    Accent("Amber", Amber),
    Accent("Green", Color(0xFF22C55E)),
    Accent("Blue", Color(0xFF3B82F6)),
    // ---- palettes ----
    Accent("Sunset", Color(0xFFFF6B35), secondary = Color(0xFFF72585), tertiary = Color(0xFFFFB547)),
    Accent("Ocean", Color(0xFF0EA5E9), secondary = Color(0xFF6366F1), tertiary = Color(0xFF2DD4BF)),
    Accent("Forest", Color(0xFF16A34A), secondary = Color(0xFF84CC16), tertiary = Color(0xFF0D9488)),
    Accent("Rose", Color(0xFFF43F5E), secondary = Color(0xFFEC4899), tertiary = Color(0xFFFDA4AF)),
    Accent("Lavender", Color(0xFFA78BFA), secondary = Color(0xFFE879F9), tertiary = Color(0xFF7DD3FC)),
    Accent("Mono", Color(0xFFA1A1AA), secondary = Color(0xFF71717A), tertiary = Color(0xFFD4D4D8))
)

// Black (AMOLED) theme: true black for backgrounds and surfaces, with just enough lift on
// containers that cards and sheets are still distinguishable.
val Black0 = Color(0xFF000000)
val Black1 = Color(0xFF0B0B0D)
val Black2 = Color(0xFF141417)
val Black3 = Color(0xFF1E1E22)

