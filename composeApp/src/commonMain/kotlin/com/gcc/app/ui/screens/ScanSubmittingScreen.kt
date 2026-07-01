package com.gcc.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.components.GccButton
import com.gcc.app.ui.components.GccButtonVariant
import com.gcc.app.ui.theme.GccGreen
import com.gcc.app.ui.theme.TextMuted

/**
 * Submitting state screen — spinner while uploading, error+retry when the call fails.
 */
@Composable
fun ScanSubmittingScreen(
    submitError: String? = null,
    onRetry: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    Scaffold(contentWindowInsets = WindowInsets(0)) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            if (submitError == null) {
                // ── Uploading ──────────────────────────────────────────────
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
            } else {
                // ── Error / retry ──────────────────────────────────────────
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = "Submission failed",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = submitError,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    GccButton(
                        text = "Retry",
                        onClick = onRetry,
                        variant = GccButtonVariant.PRIMARY,
                    )
                    GccButton(
                        text = "Back",
                        onClick = onBack,
                        variant = GccButtonVariant.OUTLINED,
                    )
                }
            }
        }
    }
}
