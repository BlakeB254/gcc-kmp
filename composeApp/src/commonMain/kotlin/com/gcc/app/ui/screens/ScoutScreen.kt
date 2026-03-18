package com.gcc.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.components.*
import com.gcc.app.ui.theme.*
import com.gcc.app.viewmodel.RouteScanUiState
import com.gcc.shared.model.DeviceType

/**
 * Scout lap screen — shown during the SCOUTING phase.
 *
 * Displays route name, device type badge, live coverage bar,
 * AI direction banner, hotspot count, and phase action buttons.
 */
@Composable
fun ScoutScreen(
    uiState: RouteScanUiState,
    onStartScout: () -> Unit,
    onCompleteScout: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // AI direction banner slides from top
            AiDirectionBanner(
                text = uiState.aiDirectionText,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .statusBarsPadding(),
            )

            // Main content
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
                    .padding(top = 72.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Route name + device badge row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            text = uiState.route?.displayName ?: "Scout Route",
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        )
                        Text(
                            text = uiState.route?.code ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                        )
                    }

                    // Device type badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (uiState.deviceType == DeviceType.AR) GccBlue.copy(alpha = 0.15f)
                                else GccGreen.copy(alpha = 0.15f)
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = if (uiState.deviceType == DeviceType.AR) "AR" else "GPS",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = if (uiState.deviceType == DeviceType.AR) GccBlue else GccGreen,
                        )
                    }
                }

                // Coverage bar
                CoverageBar(
                    percent = uiState.coveragePercent,
                    label = "COVERAGE",
                    modifier = Modifier.fillMaxWidth(),
                )

                // Loop closure status
                if (uiState.scanId != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (uiState.isLoopClosed) GccGreen else GccGold)
                        )
                        Text(
                            text = if (uiState.isLoopClosed) "Loop closed" else "Return to start to close loop",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (uiState.isLoopClosed) GccGreen else GccGold,
                        )
                    }
                }

                // Hotspot count card
                if (uiState.hotspots.isNotEmpty()) {
                    GccCard(accentColor = CardAccentRed) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Hotspots Detected",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextMuted,
                            )
                            Text(
                                text = "${uiState.hotspots.size}",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = GccError,
                            )
                        }
                    }
                }

                uiState.error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = GccError)
                }

                Spacer(modifier = Modifier.weight(1f))

                // Phase action buttons
                when {
                    uiState.isSubmitting -> {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = GccGreen, modifier = Modifier.size(32.dp))
                        }
                    }
                    uiState.scanId == null -> {
                        GccButton(
                            text = "Start Scout",
                            onClick = onStartScout,
                            variant = GccButtonVariant.PRIMARY,
                        )
                    }
                    else -> {
                        val canComplete = uiState.coveragePercent >= 85.0 && uiState.isLoopClosed
                        GccButton(
                            text = if (canComplete) "Complete Scout" else
                                "Scouting... ${uiState.coveragePercent.toInt()}%",
                            onClick = { if (canComplete) onCompleteScout() },
                            variant = GccButtonVariant.PRIMARY,
                            enabled = canComplete,
                        )
                    }
                }
            }

            // Back button
            TextButton(
                onClick = onBack,
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(4.dp)
                    .align(Alignment.TopStart),
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.9f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("\u2190", style = MaterialTheme.typography.titleMedium, color = GccGreenDark)
                }
            }
        }
    }
}
