package com.gcc.admin.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gcc.shared.theme.GccDimensions as SharedDimensions
import com.gcc.shared.theme.GccElevation as SharedElevation
import com.gcc.shared.theme.GccShapes as SharedShapes
import com.gcc.shared.theme.GccSpacing as SharedSpacing

// ── Material3 Dark Color Scheme ──────────────────────────────────────────────

val AdminDarkColorScheme = darkColorScheme(
    primary = AdminPrimary,
    onPrimary = AdminOnPrimary,
    primaryContainer = AdminPrimaryDark,
    onPrimaryContainer = AdminPrimaryLight,
    secondary = AdminSecondary,
    onSecondary = AdminOnSecondary,
    secondaryContainer = AdminSecondaryDark,
    onSecondaryContainer = AdminSecondaryLight,
    error = AdminError,
    onError = AdminOnError,
    errorContainer = AdminErrorDark,
    background = AdminBackground,
    onBackground = AdminOnBackground,
    surface = AdminSurface,
    onSurface = AdminOnSurface,
    surfaceVariant = AdminSurfaceVariant,
    onSurfaceVariant = AdminOnSurfaceVariant,
    outline = AdminOutline,
    outlineVariant = AdminOutlineVariant,
)

// ── Typography ───────────────────────────────────────────────────────────────
// Desktop-optimized sizes: slightly larger body for readability on monitors

val AdminTypography = Typography(
    displayLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = (-0.5).sp,
    ),
    displayMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
    ),
    displaySmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
    ),
    headlineLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp,
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
)

// ── Shapes ───────────────────────────────────────────────────────────────────
// Fibonacci-based radii sourced from shared GccShapes tokens

val AdminShapes = Shapes(
    extraSmall = RoundedCornerShape(SharedShapes.xs.dp),
    small = RoundedCornerShape(SharedShapes.sm.dp),
    medium = RoundedCornerShape(SharedShapes.md.dp),
    large = RoundedCornerShape(SharedShapes.lg.dp),
    extraLarge = RoundedCornerShape(SharedShapes.xl.dp),
)

// ── Spacing Tokens sourced from shared GccSpacing ────────────────────────────

@Immutable
data class AdminSpacing(
    val xxs: Dp = SharedSpacing.xxs.dp,
    val xs: Dp = SharedSpacing.xs.dp,
    val sm: Dp = SharedSpacing.sm.dp,
    val md: Dp = SharedSpacing.md.dp,
    val lg: Dp = SharedSpacing.lg.dp,
    val xl: Dp = SharedSpacing.xl.dp,
    val xxl: Dp = SharedSpacing.xxl.dp,
    val xxxl: Dp = SharedSpacing.xxxl.dp,
)

// ── Component Dimensions sourced from shared GccDimensions ───────────────────

@Immutable
data class AdminDimensions(
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
    // Touch targets
    val touchTarget: Dp = SharedDimensions.touchTarget.dp,
)

// ── Elevation Scale sourced from shared GccElevation ─────────────────────────

@Immutable
data class AdminElevation(
    val none: Dp = SharedElevation.none.dp,
    val subtle: Dp = SharedElevation.subtle.dp,
    val card: Dp = SharedElevation.card.dp,
    val raised: Dp = SharedElevation.raised.dp,
    val floating: Dp = SharedElevation.floating.dp,
    val overlay: Dp = SharedElevation.overlay.dp,
    val modal: Dp = SharedElevation.modal.dp,
)

// ── Composition Locals ───────────────────────────────────────────────────────

val LocalAdminSpacing = staticCompositionLocalOf { AdminSpacing() }
val LocalAdminDimensions = staticCompositionLocalOf { AdminDimensions() }
val LocalAdminElevation = staticCompositionLocalOf { AdminElevation() }

// ── Theme Accessor Object ────────────────────────────────────────────────────

object AdminTheme {
    val spacing: AdminSpacing
        @Composable get() = LocalAdminSpacing.current

    val dimensions: AdminDimensions
        @Composable get() = LocalAdminDimensions.current

    val elevation: AdminElevation
        @Composable get() = LocalAdminElevation.current
}

// ── Theme Composable ─────────────────────────────────────────────────────────

@Composable
fun AdminTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalAdminSpacing provides AdminSpacing(),
        LocalAdminDimensions provides AdminDimensions(),
        LocalAdminElevation provides AdminElevation(),
    ) {
        MaterialTheme(
            colorScheme = AdminDarkColorScheme,
            typography = AdminTypography,
            shapes = AdminShapes,
            content = content,
        )
    }
}
