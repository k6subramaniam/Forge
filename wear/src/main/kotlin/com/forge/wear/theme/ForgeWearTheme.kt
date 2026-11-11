package com.forge.wear.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme

// Same palette as the phone app and the mockups: amber accent on charcoal/black.
private val ForgeWearColors = Colors(
    primary = Color(0xFFF2A93B),
    primaryVariant = Color(0xFFB97A1E),
    secondary = Color(0xFF5EA8FF),
    secondaryVariant = Color(0xFF3A6EA8),
    background = Color(0xFF000000),
    surface = Color(0xFF121218),
    error = Color(0xFFFF6B6B),
    onPrimary = Color(0xFF171106),
    onSecondary = Color(0xFF000000),
    onBackground = Color(0xFFF2F2F5),
    onSurface = Color(0xFFF2F2F5),
    onError = Color(0xFF000000),
    onSurfaceVariant = Color(0xFF9A9AAD),
)

@Composable
fun ForgeWearTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = ForgeWearColors,
        content = content,
    )
}
