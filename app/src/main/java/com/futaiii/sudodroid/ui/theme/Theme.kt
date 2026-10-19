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

// Warm charcoal: 171312 canvas, 2D2929 cards, 383434 selection, D3C3C2 text/strokes, D6B0AE accent.
object Palette {
    val Canvas = Color(0xFF171312)
    val Card = Color(0xFF2D2929)
    val Selected = Color(0xFF383434)
    val Ink = Color(0xFFD3C3C2)
    val Rose = Color(0xFFD6B0AE)
    val Sage = Color(0xFF8FB8A3)
}

val DarkColors: ColorScheme = darkColorScheme(
    primary = Palette.Rose,
    onPrimary = Color(0xFF2D1B1A),
    primaryContainer = Palette.Selected,
    onPrimaryContainer = Palette.Ink,
    inversePrimary = Color(0xFF8A6462),
    secondary = Palette.Ink,
    onSecondary = Palette.Canvas,
    secondaryContainer = Palette.Selected,
    onSecondaryContainer = Palette.Ink,
    tertiary = Palette.Sage,
    onTertiary = Color(0xFF0B2016),
    tertiaryContainer = Color(0xFF1D3A2C),
    onTertiaryContainer = Color(0xFFB3E0C8),
    background = Palette.Canvas,
    onBackground = Palette.Ink,
    surface = Palette.Canvas,
    onSurface = Palette.Ink,
    surfaceVariant = Palette.Card,
    onSurfaceVariant = Color(0xFFA99B9A),
    surfaceTint = Color.Transparent,
    inverseSurface = Palette.Ink,
    inverseOnSurface = Palette.Canvas,
    outline = Palette.Ink,
    outlineVariant = Color(0xFF4A4343),
    error = Color(0xFFE8A09A),
    onError = Color(0xFF3B0A07),
    errorContainer = Color(0xFF5A1F1B),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color(0xFF000000),
    surfaceBright = Palette.Selected,
    surfaceDim = Palette.Canvas,
    surfaceContainerLowest = Palette.Canvas,
    surfaceContainerLow = Palette.Canvas,
    surfaceContainer = Palette.Card,
    surfaceContainerHigh = Palette.Card,
    surfaceContainerHighest = Palette.Selected
)

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(40.dp)
)

private val Sans = FontFamily.SansSerif

val AppTypography: Typography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold),
        displayMedium = base.displayMedium.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold),
        displaySmall = base.displaySmall.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold),
        headlineLarge = base.headlineLarge.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineMedium = base.headlineMedium.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
        headlineSmall = base.headlineSmall.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleLarge = base.titleLarge.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
        titleSmall = base.titleSmall.copy(fontFamily = Sans, fontWeight = FontWeight.Medium),
        bodyLarge = base.bodyLarge.copy(fontFamily = Sans),
        bodyMedium = base.bodyMedium.copy(fontFamily = Sans),
        bodySmall = base.bodySmall.copy(fontFamily = Sans),
        labelLarge = base.labelLarge.copy(fontFamily = Sans, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
        labelMedium = base.labelMedium.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold, letterSpacing = 1.0.sp),
        labelSmall = base.labelSmall.copy(fontFamily = Sans, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp)
    )
}

/** Large figures for the live speed readout; tabular digits keep the width steady as values change. */
val SpeedFigure = TextStyle(
    fontFamily = Sans,
    fontWeight = FontWeight.SemiBold,
    fontSize = 36.sp,
    lineHeight = 40.sp,
    letterSpacing = (-0.5).sp,
    fontFeatureSettings = "tnum"
)

/** Flat canvas colour; the screens carry no decorative gradient. */
val AppBackgroundBrush: Brush = Brush.verticalGradient(colors = listOf(Palette.Canvas, Palette.Canvas))

@Composable
fun SudodroidTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
