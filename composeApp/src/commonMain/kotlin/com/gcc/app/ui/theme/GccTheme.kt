package com.gcc.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val GccColorScheme = lightColorScheme(
    primary = GccGreen,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = GccGreenLight,
    secondary = GccGreenDark,
    background = GccBackground,
    surface = GccSurface,
    onBackground = GccOnBackground,
    onSurface = GccOnSurface,
    error = GccError,
)

@Composable
fun GccTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GccColorScheme,
        typography = GccTypography,
        content = content
    )
}
