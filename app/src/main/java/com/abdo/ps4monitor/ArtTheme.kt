package com.abdo.ps4monitor

import android.graphics.Bitmap
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.palette.graphics.Palette
import kotlin.math.max
import kotlin.math.min

/**
 * Extract a usable seed colour from game artwork (icon0 / pic0 / pic1).
 * Prefers vibrant → muted → dominant, and rejects near-black / near-white.
 */
fun seedFromBitmap(bmp: Bitmap?): Int? {
    if (bmp == null || bmp.width < 8 || bmp.height < 8) return null
    return runCatching {
        val p = Palette.from(bmp).maximumColorCount(16).generate()
        val candidates = listOfNotNull(
            p.vibrantSwatch, p.lightVibrantSwatch, p.darkVibrantSwatch,
            p.mutedSwatch, p.lightMutedSwatch, p.darkMutedSwatch, p.dominantSwatch
        )
        val best = candidates.firstOrNull { sw ->
            val hsl = FloatArray(3)
            android.graphics.Color.colorToHSV(sw.rgb, hsl)
            // reject near-black / near-white / very low saturation
            hsl[2] in 0.12f..0.92f && hsl[1] >= 0.12f
        } ?: candidates.firstOrNull()
        best?.rgb
    }.getOrNull()
}

/** Build a full Material3 ColorScheme from a seed ARGB colour. */
fun schemeFromSeed(seedArgb: Int, dark: Boolean): ColorScheme {
    val seed = Color(seedArgb)
    // Derive a richer palette by shifting HSL of the seed.
    fun shift(c: Color, h: Float = 0f, s: Float = 0f, l: Float = 0f): Color {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(c.toArgb(), hsv)
        hsv[0] = (hsv[0] + h + 360f) % 360f
        hsv[1] = (hsv[1] + s).coerceIn(0f, 1f)
        hsv[2] = (hsv[2] + l).coerceIn(0f, 1f)
        return Color(android.graphics.Color.HSVToColor(hsv))
    }
    fun blend(a: Color, b: Color, t: Float) = Color(
        red = a.red + (b.red - a.red) * t,
        green = a.green + (b.green - a.green) * t,
        blue = a.blue + (b.blue - a.blue) * t,
        alpha = 1f
    )
    val primary = if (dark) shift(seed, l = 0.18f, s = 0.05f) else shift(seed, l = -0.08f, s = 0.08f)
    val secondary = shift(seed, h = 28f, s = -0.15f, l = if (dark) 0.12f else -0.05f)
    val tertiary = shift(seed, h = -40f, s = -0.1f, l = if (dark) 0.1f else -0.02f)
    val surface = if (dark) Color(0xFF121212) else Color(0xFFFFFBFE)
    val surfaceContainer = if (dark) blend(surface, primary, 0.08f) else blend(surface, primary, 0.06f)
    val primaryContainer = if (dark) blend(primary, Color.Black, 0.45f) else blend(primary, Color.White, 0.72f)
    val secondaryContainer = if (dark) blend(secondary, Color.Black, 0.5f) else blend(secondary, Color.White, 0.78f)
    val tertiaryContainer = if (dark) blend(tertiary, Color.Black, 0.5f) else blend(tertiary, Color.White, 0.78f)
    val onPrimary = if (primary.luminance() > 0.45f) Color.Black else Color.White
    val onSecondary = if (secondary.luminance() > 0.45f) Color.Black else Color.White
    val onTertiary = if (tertiary.luminance() > 0.45f) Color.Black else Color.White
    val onPrimaryContainer = if (primaryContainer.luminance() > 0.45f) Color(0xFF1A1A1A) else Color(0xFFF5F5F5)
    val onSurface = if (dark) Color(0xFFE6E1E5) else Color(0xFF1C1B1F)
    return if (dark) darkColorScheme(
        primary = primary, onPrimary = onPrimary,
        primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
        secondary = secondary, onSecondary = onSecondary,
        secondaryContainer = secondaryContainer, onSecondaryContainer = onPrimaryContainer,
        tertiary = tertiary, onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer, onTertiaryContainer = onPrimaryContainer,
        surface = surface, onSurface = onSurface,
        surfaceContainer = surfaceContainer,
        surfaceContainerLow = blend(surface, primary, 0.04f),
        surfaceContainerHigh = blend(surface, primary, 0.12f),
        surfaceContainerHighest = blend(surface, primary, 0.16f),
        background = surface, onBackground = onSurface,
        error = Color(0xFFFFB4AB), onError = Color(0xFF690005)
    ) else lightColorScheme(
        primary = primary, onPrimary = onPrimary,
        primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
        secondary = secondary, onSecondary = onSecondary,
        secondaryContainer = secondaryContainer, onSecondaryContainer = onPrimaryContainer,
        tertiary = tertiary, onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer, onTertiaryContainer = onPrimaryContainer,
        surface = surface, onSurface = onSurface,
        surfaceContainer = surfaceContainer,
        surfaceContainerLow = blend(surface, primary, 0.03f),
        surfaceContainerHigh = blend(surface, primary, 0.08f),
        surfaceContainerHighest = blend(surface, primary, 0.12f),
        background = surface, onBackground = onSurface,
        error = Color(0xFFBA1A1A), onError = Color.White
    )
}

/** Soft tint for cards (surfaceContainer mixed with seed). Returns null when no art. */
fun cardTintFromSeed(seedArgb: Int?, dark: Boolean): Color? {
    if (seedArgb == null) return null
    val seed = Color(seedArgb)
    val base = if (dark) Color(0xFF1E1E1E) else Color(0xFFF7F2FA)
    return Color(
        red = base.red + (seed.red - base.red) * 0.18f,
        green = base.green + (seed.green - base.green) * 0.18f,
        blue = base.blue + (seed.blue - base.blue) * 0.18f,
        alpha = 1f
    )
}

@Composable
fun rememberArtScheme(bmp: Bitmap?, fallback: ColorScheme = MaterialTheme.colorScheme): ColorScheme {
    // Prefer the ambient scheme's luminance so we respect the app theme setting (System / Light / Dark).
    val dark = fallback.background.luminance() < 0.3f
    return remember(bmp, dark, fallback) {
        val seed = seedFromBitmap(bmp) ?: return@remember fallback
        schemeFromSeed(seed, dark)
    }
}

@Composable
fun ArtTheme(bmp: Bitmap?, content: @Composable () -> Unit) {
    val scheme = rememberArtScheme(bmp)
    MaterialTheme(colorScheme = scheme, shapes = MaterialTheme.shapes, typography = MaterialTheme.typography, content = content)
}

/** ARGB colour for NotificationCompat.setColor — cached lightly by bitmap identity. */
fun notifColorFromBitmap(bmp: Bitmap?): Int {
    val seed = seedFromBitmap(bmp) ?: return 0xFF6A3DE8.toInt()
    // Slightly lighten so it reads well on the notification accent strip
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(seed, hsv)
    hsv[1] = min(1f, hsv[1] * 1.05f)
    hsv[2] = max(0.35f, min(0.85f, hsv[2]))
    return android.graphics.Color.HSVToColor(hsv)
}
