package com.gcc.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val GccColorScheme = lightColorScheme(
    primary = GccGreen,
    onPrimary = Color.White,
    primaryContainer = GccGreenLight,
    onPrimaryContainer = GccGreenDark,
    secondary = GccGold,
    onSecondary = Color.White,
    secondaryContainer = GccGoldLight,
    onSecondaryContainer = GccGoldDark,
    tertiary = GccBlue,
    onTertiary = Color.White,
    tertiaryContainer = GccBlueLight,
    onTertiaryContainer = GccBlueDark,
    background = GccBackground,
    onBackground = GccOnBackground,
    surface = GccSurface,
    onSurface = GccOnSurface,
    surfaceVariant = SurfaceMuted,
    onSurfaceVariant = TextMuted,
    outline = DividerColor,
    outlineVariant = DividerColor,
    error = GccError,
)

// Fibonacci-based shapes (3, 5, 8, 13, 21dp radii)
private val GccShapes = Shapes(
    extraSmall = RoundedCornerShape(3.dp),
    small = RoundedCornerShape(5.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(13.dp),
    extraLarge = RoundedCornerShape(21.dp),
)

// Fibonacci spacing tokens: 3, 5, 8, 13, 21, 34, 55, 89dp
@Immutable
data class GccSpacing(
    val xxs: Dp = 3.dp,
    val xs: Dp = 5.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 13.dp,
    val lg: Dp = 21.dp,
    val xl: Dp = 34.dp,
    val xxl: Dp = 55.dp,
    val xxxl: Dp = 89.dp,
)

// φ-derived component dimensions using Fibonacci numbers
@Immutable
data class GccDimensions(
    val phi: Float = 1.618034f,
    // Icons
    val iconSm: Dp = 21.dp,
    val iconMd: Dp = 34.dp,
    val iconLg: Dp = 55.dp,
    // Avatars
    val avatarSm: Dp = 34.dp,
    val avatarMd: Dp = 55.dp,
    val avatarLg: Dp = 89.dp,
    // Cards
    val cardMinHeight: Dp = 89.dp,
    val cardRadius: Dp = 13.dp,
    // Buttons
    val buttonHeight: Dp = 55.dp,
    val buttonRadiusSmall: Dp = 8.dp,
    val buttonRadiusLarge: Dp = 13.dp,
    // Touch targets
    val touchTarget: Dp = 55.dp,
)

// Fibonacci elevation scale: 0, 1, 2, 3, 5, 8, 13dp
@Immutable
data class GccElevation(
    val none: Dp = 0.dp,
    val subtle: Dp = 1.dp,
    val card: Dp = 2.dp,
    val raised: Dp = 3.dp,
    val floating: Dp = 5.dp,
    val overlay: Dp = 8.dp,
    val modal: Dp = 13.dp,
)

val LocalGccSpacing = staticCompositionLocalOf { GccSpacing() }
val LocalGccDimensions = staticCompositionLocalOf { GccDimensions() }
val LocalGccElevation = staticCompositionLocalOf { GccElevation() }

object GccTheme {
    val spacing: GccSpacing
        @Composable get() = LocalGccSpacing.current

    val dimensions: GccDimensions
        @Composable get() = LocalGccDimensions.current

    val elevation: GccElevation
        @Composable get() = LocalGccElevation.current
}

@Composable
fun GccTheme(content: @Composable () -> Unit) {
    val typography = gccTypography()

    CompositionLocalProvider(
        LocalGccSpacing provides GccSpacing(),
        LocalGccDimensions provides GccDimensions(),
        LocalGccElevation provides GccElevation(),
    ) {
        MaterialTheme(
            colorScheme = GccColorScheme,
            typography = typography,
            shapes = GccShapes,
            content = content,
        )
    }
}
