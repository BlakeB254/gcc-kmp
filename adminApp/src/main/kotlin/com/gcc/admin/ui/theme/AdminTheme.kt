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
// Fibonacci-based radii: 3, 5, 8, 13, 21dp

val AdminShapes = Shapes(
    extraSmall = RoundedCornerShape(3.dp),
    small = RoundedCornerShape(5.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(13.dp),
    extraLarge = RoundedCornerShape(21.dp),
)

// ── Fibonacci Spacing Tokens: 3, 5, 8, 13, 21, 34, 55, 89dp ────────────────

@Immutable
data class AdminSpacing(
    val xxs: Dp = 3.dp,
    val xs: Dp = 5.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 13.dp,
    val lg: Dp = 21.dp,
    val xl: Dp = 34.dp,
    val xxl: Dp = 55.dp,
    val xxxl: Dp = 89.dp,
)

// ── Fibonacci-derived Component Dimensions ───────────────────────────────────

@Immutable
data class AdminDimensions(
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
    // Touch targets
    val touchTarget: Dp = 55.dp,
)

// ── Fibonacci Elevation Scale: 0, 1, 2, 3, 5, 8, 13dp ──────────────────────

@Immutable
data class AdminElevation(
    val none: Dp = 0.dp,
    val subtle: Dp = 1.dp,
    val card: Dp = 2.dp,
    val raised: Dp = 3.dp,
    val floating: Dp = 5.dp,
    val overlay: Dp = 8.dp,
    val modal: Dp = 13.dp,
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
