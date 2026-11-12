package com.forge.mobile.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Palette lifted from the Forge UI design visuals:
// deep charcoal surfaces, off-white text, a single amber accent.
val ForgeAmber = Color(0xFFF2A93B)
val ForgeAmberLight = Color(0xFFFFCF7D)
val ForgeBackground = Color(0xFF0B0B0F)
val ForgeSurface = Color(0xFF121218)
val ForgeSurfaceVariant = Color(0xFF26262F)
val ForgeOnSurface = Color(0xFFF2F2F5)
val ForgeMuted = Color(0xFF9A9AAD)
val ForgeGreen = Color(0xFF4ADE80)
val ForgeBlue = Color(0xFF5EA8FF)
val ForgePurple = Color(0xFFB18CFF)
val ForgeRed = Color(0xFFFF6B6B)

private val ForgeDarkColorScheme = darkColorScheme(
    primary = ForgeAmber,
    onPrimary = Color(0xFF171106),
    secondary = ForgeBlue,
    onSecondary = Color(0xFF0B0B0F),
    tertiary = ForgePurple,
    background = ForgeBackground,
    onBackground = ForgeOnSurface,
    surface = ForgeSurface,
    onSurface = ForgeOnSurface,
    surfaceVariant = ForgeSurfaceVariant,
    onSurfaceVariant = ForgeMuted,
    error = ForgeRed,
    onError = Color(0xFF0B0B0F),
)

@Composable
fun ForgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ForgeDarkColorScheme,
        content = content,
    )
}
