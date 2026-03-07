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
import com.gcc.shared.theme.GccDimensions as SharedDimensions
import com.gcc.shared.theme.GccElevation as SharedElevation
import com.gcc.shared.theme.GccShapes as SharedShapes
import com.gcc.shared.theme.GccSpacing as SharedSpacing

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

// Fibonacci-based shapes sourced from shared GccShapes tokens
private val GccShapes = Shapes(
    extraSmall = RoundedCornerShape(SharedShapes.xs.dp),
    small = RoundedCornerShape(SharedShapes.sm.dp),
    medium = RoundedCornerShape(SharedShapes.md.dp),
    large = RoundedCornerShape(SharedShapes.lg.dp),
    extraLarge = RoundedCornerShape(SharedShapes.xl.dp),
)

// Spacing tokens sourced from shared GccSpacing
@Immutable
data class GccSpacing(
    val xxs: Dp = SharedSpacing.xxs.dp,
    val xs: Dp = SharedSpacing.xs.dp,
    val sm: Dp = SharedSpacing.sm.dp,
    val md: Dp = SharedSpacing.md.dp,
    val lg: Dp = SharedSpacing.lg.dp,
    val xl: Dp = SharedSpacing.xl.dp,
    val xxl: Dp = SharedSpacing.xxl.dp,
    val xxxl: Dp = SharedSpacing.xxxl.dp,
)

// Component dimensions sourced from shared GccDimensions
@Immutable
data class GccDimensions(
    val phi: Float = SharedDimensions.phi,
    // Icons
    val iconSm: Dp = SharedDimensions.iconSm.dp,
    val iconMd: Dp = SharedDimensions.iconMd.dp,
    val iconLg: Dp = SharedDimensions.iconLg.dp,
    // Avatars
    val avatarSm: Dp = SharedDimensions.avatarSm.dp,
    val avatarMd: Dp = SharedDimensions.avatarMd.dp,
    val avatarLg: Dp = SharedDimensions.avatarLg.dp,
    // Cards
    val cardMinHeight: Dp = SharedDimensions.cardMinHeight.dp,
    val cardRadius: Dp = SharedDimensions.cardRadius.dp,
    // Buttons
    val buttonHeight: Dp = SharedDimensions.buttonHeight.dp,
    val buttonRadiusSmall: Dp = SharedDimensions.buttonRadiusSmall.dp,
    val buttonRadiusLarge: Dp = SharedDimensions.buttonRadiusLarge.dp,
    // Touch targets
    val touchTarget: Dp = SharedDimensions.touchTarget.dp,
)

// Elevation scale sourced from shared GccElevation
@Immutable
data class GccElevation(
    val none: Dp = SharedElevation.none.dp,
    val subtle: Dp = SharedElevation.subtle.dp,
    val card: Dp = SharedElevation.card.dp,
    val raised: Dp = SharedElevation.raised.dp,
    val floating: Dp = SharedElevation.floating.dp,
    val overlay: Dp = SharedElevation.overlay.dp,
    val modal: Dp = SharedElevation.modal.dp,
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
