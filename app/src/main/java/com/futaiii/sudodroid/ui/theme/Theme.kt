package com.futaiii.sudodroid.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF155E75),
    onPrimary = Color.White,
    secondary = Color(0xFFF49F0A),
    onSecondary = Color(0xFF0B1720),
    background = Color(0xFF0B1720),
    onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF10212E),
    onSurface = Color(0xFFE2E8F0),
    outline = Color(0xFF304452)
)

val AppTypography = Typography()

@Composable
fun SudodroidTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else DarkColors,
        typography = AppTypography,
        content = content
    )
}
