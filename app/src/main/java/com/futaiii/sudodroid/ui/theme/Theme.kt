package com.futaiii.sudodroid.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Charcoal neutrals with a champagne accent; "connected" uses a muted sage so it never shouts.
object Palette {
    val Ink = Color(0xFF0B0B0D)
    val Champagne = Color(0xFFD9BF8C)
    val ChampagneDeep = Color(0xFF8F7742)
    val Sage = Color(0xFF8FB8A3)
}

val DarkColors: ColorScheme = darkColorScheme(
    primary = Palette.Champagne,
    onPrimary = Color(0xFF2A1F08),
    primaryContainer = Color(0xFF3A2F18),
    onPrimaryContainer = Color(0xFFF2DFB8),
    inversePrimary = Palette.ChampagneDeep,
    secondary = Color(0xFFB8AFA0),
    onSecondary = Color(0xFF211D14),
    secondaryContainer = Color(0xFF2E2A22),
    onSecondaryContainer = Color(0xFFE4DBC9),
    tertiary = Palette.Sage,
    onTertiary = Color(0xFF0B2016),
    tertiaryContainer = Color(0xFF1D3A2C),
    onTertiaryContainer = Color(0xFFB3E0C8),
    background = Palette.Ink,
    onBackground = Color(0xFFEAE6DE),
    surface = Color(0xFF111113),
    onSurface = Color(0xFFEAE6DE),
    surfaceVariant = Color(0xFF26252A),
    onSurfaceVariant = Color(0xFF9F9A90),
    surfaceTint = Palette.Champagne,
    inverseSurface = Color(0xFFEAE6DE),
    inverseOnSurface = Color(0xFF1A1A1D),
    outline = Color(0xFF55524A),
    outlineVariant = Color(0xFF2B2A27),
    error = Color(0xFFE8A09A),
    onError = Color(0xFF3B0A07),
    errorContainer = Color(0xFF5A1F1B),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF2C2B30),
    surfaceDim = Color(0xFF0B0B0D),
    surfaceContainerLowest = Color(0xFF09090B),
    surfaceContainerLow = Color(0xFF141416),
    surfaceContainer = Color(0xFF18181B),
    surfaceContainerHigh = Color(0xFF1F1F23),
    surfaceContainerHighest = Color(0xFF27272C)
)

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(40.dp)
)

private val Display = FontFamily.Serif
private val Body = FontFamily.Default

val AppTypography: Typography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = Display, fontWeight = FontWeight.Light),
        displayMedium = base.displayMedium.copy(fontFamily = Display, fontWeight = FontWeight.Light),
        displaySmall = base.displaySmall.copy(fontFamily = Display, fontWeight = FontWeight.Light),
        headlineLarge = base.headlineLarge.copy(fontFamily = Display, fontWeight = FontWeight.Normal),
        headlineMedium = base.headlineMedium.copy(fontFamily = Display, fontWeight = FontWeight.Normal),
        headlineSmall = base.headlineSmall.copy(fontFamily = Display, fontWeight = FontWeight.Normal),
        titleLarge = base.titleLarge.copy(fontFamily = Body, fontWeight = FontWeight.Medium),
        titleMedium = base.titleMedium.copy(fontFamily = Body, fontWeight = FontWeight.Medium, letterSpacing = 0.15.sp),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
        labelMedium = base.labelMedium.copy(letterSpacing = 0.8.sp),
        labelSmall = base.labelSmall.copy(letterSpacing = 1.0.sp)
    )
}

/** Large, tabular-looking figures for the live speed readout. */
val SpeedFigure = TextStyle(
    fontFamily = Display,
    fontWeight = FontWeight.Light,
    fontSize = 40.sp,
    lineHeight = 44.sp,
    letterSpacing = (-0.5).sp
)

/** Faint warm glow at the top fading into ink; the only decoration behind the screens. */
val AppBackgroundBrush: Brush = Brush.verticalGradient(
    colors = listOf(Color(0xFF1B1812), Color(0xFF111012), Palette.Ink)
)

@Composable
fun SudodroidTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
