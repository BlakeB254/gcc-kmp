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

val AdminShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

// ── Custom Spacing Tokens ────────────────────────────────────────────────────

@Immutable
data class AdminSpacing(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 20.dp,
    val xxl: Dp = 24.dp,
    val xxxl: Dp = 32.dp,
    val section: Dp = 40.dp,
)

// ── Custom Elevation Tokens ──────────────────────────────────────────────────

@Immutable
data class AdminElevation(
    val none: Dp = 0.dp,
    val card: Dp = 2.dp,
    val raised: Dp = 4.dp,
    val floating: Dp = 8.dp,
    val overlay: Dp = 16.dp,
)

// ── Composition Locals ───────────────────────────────────────────────────────

val LocalAdminSpacing = staticCompositionLocalOf { AdminSpacing() }
val LocalAdminElevation = staticCompositionLocalOf { AdminElevation() }

object AdminThemeTokens {
    val spacing: AdminSpacing
        @Composable get() = LocalAdminSpacing.current

    val elevation: AdminElevation
        @Composable get() = LocalAdminElevation.current
}

// ── Theme Composable ─────────────────────────────────────────────────────────

@Composable
fun AdminTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalAdminSpacing provides AdminSpacing(),
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
