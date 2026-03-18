package com.gcc.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.components.*
import com.gcc.app.ui.theme.*
import com.gcc.app.viewmodel.RouteScanUiState

/**
 * Scout review summary screen — shows coverage, hotspot count, elapsed time.
 * Confirms scout lap before beginning the cleaning phase.
 */
@Composable
fun ScoutReviewScreen(
    uiState: RouteScanUiState,
    onStartCleaning: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(contentWindowInsets = WindowInsets(0)) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Scout Complete!",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = GccGreen,
            )

            Text(
                text = "Review your scout results and start the cleaning lap.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
                textAlign = TextAlign.Center,
            )

            // Summary card
            GccCard(accentColor = CardAccentGreen) {
                ScanSummaryRow(label = "Coverage", value = "${uiState.coveragePercent.toInt()}%")
                ScanSummaryRow(label = "Hotspots Found", value = "${uiState.hotspots.size}")
                ScanSummaryRow(
                    label = "Scout Time",
                    value = formatElapsed(uiState.elapsedSeconds),
                )
                ScanSummaryRow(
                    label = "Loop Closed",
                    value = if (uiState.isLoopClosed) "Yes" else "No",
                    valueColor = if (uiState.isLoopClosed) GccGreen else GccGold,
                )
            }

            uiState.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = GccError)
            }

            Spacer(modifier = Modifier.weight(1f))

            GccButton(
                text = "Start Cleaning",
                onClick = onStartCleaning,
                variant = GccButtonVariant.PRIMARY,
                isLoading = uiState.isSubmitting,
            )

            GccButton(
                text = "Back",
                onClick = onBack,
                variant = GccButtonVariant.OUTLINED,
                enabled = !uiState.isSubmitting,
            )
        }
    }
}
