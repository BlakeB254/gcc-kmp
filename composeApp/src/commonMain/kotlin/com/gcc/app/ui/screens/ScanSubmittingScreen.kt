package com.gcc.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.theme.GccGreen
import com.gcc.app.ui.theme.TextMuted

/**
 * Submitting state screen — shown while uploading the completed scan.
 */
@Composable
fun ScanSubmittingScreen() {
    Scaffold(contentWindowInsets = WindowInsets(0)) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator(
                    color = GccGreen,
                    strokeWidth = 3.dp,
                )
                Text(
                    text = "Submitting...",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextMuted,
                )
            }
        }
    }
}
