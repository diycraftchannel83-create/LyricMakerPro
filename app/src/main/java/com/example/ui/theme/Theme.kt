package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = NeonCyan,
    onPrimary = Color(0xFF00382E),
    primaryContainer = Color(0xFF005143),
    onPrimaryContainer = Color(0xFF70F7E0),

    secondary = NeonMagenta,
    onSecondary = Color(0xFF45001E),
    secondaryContainer = Color(0xFF6B0032),
    onSecondaryContainer = Color(0xFFFFD9E2),

    tertiary = ElectricPurple,
    onTertiary = Color(0xFF33005A),
    tertiaryContainer = Color(0xFF4C0084),
    onTertiaryContainer = Color(0xFFEADBFF),

    background = StudioBackground,
    onBackground = TextPrimary,
    surface = StudioSurface,
    onSurface = TextPrimary,
    surfaceVariant = StudioSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = StudioCardBorder
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Keep consistent dark studio theme for video editor
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
