package com.gcc.app.ui.gamification

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Composable share button that captures and shares content.
 *
 * Uses Capturable to capture the parent composable as an image,
 * then triggers platform-specific sharing via ShareManager.
 */
@Composable
fun ShareButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    text: String = "Share",
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
        ),
    ) {
        Text("\u2197 $text") // ↗ arrow
    }
}
