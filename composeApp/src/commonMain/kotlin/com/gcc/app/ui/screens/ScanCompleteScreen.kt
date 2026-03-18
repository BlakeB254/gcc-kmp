package com.gcc.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.gcc.shared.model.HotspotStatus

/**
 * Scan complete results screen — shows points awarded, hotspot stats, elapsed time.
 */
@Composable
fun ScanCompleteScreen(
    uiState: RouteScanUiState,
    onDone: () -> Unit,
) {
    val cleaned = uiState.hotspots.count { it.status == HotspotStatus.CLEANED }
    val skipped = uiState.hotspots.count { it.status == HotspotStatus.SKIPPED }
    val total = uiState.hotspots.size

    Scaffold(contentWindowInsets = WindowInsets(0)) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item { Spacer(modifier = Modifier.height(32.dp)) }

            // Success header
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "\u2705",
                        style = MaterialTheme.typography.displayMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Scan Complete!",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = GccGreen,
                    )
                    Text(
                        text = uiState.route?.displayName ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            // Points card
            if (uiState.pointsAwarded > 0) {
                item {
                    GccCard(accentColor = CardAccentGold) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(
                                    text = "Points Earned",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                )
                                Text(
                                    text = "Scout & Clean complete!",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted,
                                )
                            }
                            Text(
                                text = "+${uiState.pointsAwarded}",
                                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                                color = GccGold,
                            )
                        }
                    }
                }
            }

            // Stats card
            item {
                GccCard(accentColor = CardAccentGreen) {
                    Text(
                        text = "Scan Summary",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    ScanSummaryRow(label = "Coverage", value = "${uiState.coveragePercent.toInt()}%")
                    ScanSummaryRow(label = "Hotspots Found", value = "$total")
                    ScanSummaryRow(label = "Cleaned", value = "$cleaned", valueColor = GccGreen)
                    ScanSummaryRow(label = "Skipped", value = "$skipped", valueColor = TextMuted)
                    ScanSummaryRow(label = "Total Time", value = formatElapsed(uiState.elapsedSeconds))
                    ScanSummaryRow(
                        label = "Device",
                        value = uiState.deviceType.name,
                    )
                }
            }

            item {
                GccButton(
                    text = "Done",
                    onClick = onDone,
                    variant = GccButtonVariant.PRIMARY,
                )
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}
