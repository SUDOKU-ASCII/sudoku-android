package com.futaiii.sudodroid.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF5FA8D3),
    onPrimary = Color(0xFF07131A),
    primaryContainer = Color(0xFF143849),
    onPrimaryContainer = Color(0xFFBEE9FF),
    secondary = Color(0xFF7C92A6),
    onSecondary = Color(0xFF0E141A),
    secondaryContainer = Color(0xFF223041),
    onSecondaryContainer = Color(0xFFD6E4F0),
    tertiary = Color(0xFF79B8B0),
    onTertiary = Color(0xFF071615),
    tertiaryContainer = Color(0xFF123936),
    onTertiaryContainer = Color(0xFFAEEBE3),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE6EDF7),
    surface = Color(0xFF101826),
    onSurface = Color(0xFFE6EDF7),
    surfaceVariant = Color(0xFF162234),
    onSurfaceVariant = Color(0xFF9EADBF),
    surfaceTint = Color(0xFF5FA8D3),
    outline = Color(0xFF314053),
    outlineVariant = Color(0xFF202C3A),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color(0xFF000000)
)

val AppTypography = Typography()

@Composable
fun SudodroidTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = AppTypography,
        content = content
    )
}
