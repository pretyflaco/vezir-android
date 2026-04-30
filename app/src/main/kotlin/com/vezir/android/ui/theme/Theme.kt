package com.vezir.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Vezir brand tokens.
 *
 * Source of truth: assets/logo/README.md in pretyflaco/vezir. Three colors,
 * no gradients, no tints, no fourth color. Coral is used sparingly — only
 * the audio-dot accent and the single primary CTA per screen.
 */
val VezirInk: Color = Color(0xFF111111)
val VezirSurface: Color = Color(0xFFFFFFFF)
val VezirCoral: Color = Color(0xFFFF6B35)

// Subtle ink variants for borders / secondary text. Derived, not new
// brand tokens — they are alpha blends of ink on surface. Keeps the
// palette honest while letting Material3 distinguish pressed/disabled
// states.
val VezirInkMuted: Color = Color(0xFF6B6B6B)   // ~58% of ink on white
val VezirInkFaint: Color = Color(0xFFE0E0E0)   // ~12% of ink on white

private val LightColors = lightColorScheme(
    primary = VezirCoral,
    onPrimary = VezirSurface,
    primaryContainer = VezirCoral,
    onPrimaryContainer = VezirSurface,
    secondary = VezirInk,
    onSecondary = VezirSurface,
    background = VezirSurface,
    onBackground = VezirInk,
    surface = VezirSurface,
    onSurface = VezirInk,
    surfaceVariant = VezirInkFaint,
    onSurfaceVariant = VezirInkMuted,
    outline = VezirInkMuted,
    error = VezirCoral,                      // brand-consistent error treatment
    onError = VezirSurface,
)

private val DarkColors = darkColorScheme(
    primary = VezirCoral,
    onPrimary = VezirSurface,
    primaryContainer = VezirCoral,
    onPrimaryContainer = VezirSurface,
    secondary = VezirSurface,
    onSecondary = VezirInk,
    background = VezirInk,
    onBackground = VezirSurface,
    surface = VezirInk,
    onSurface = VezirSurface,
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFFB6B6B6),
    outline = Color(0xFF8A8A8A),
    error = VezirCoral,
    onError = VezirSurface,
)

@Composable
fun VezirTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
