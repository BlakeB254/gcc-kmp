package com.gcc.app.ui.screens

import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gcc.app.platform.ShareManager
import com.gcc.app.ui.components.*
import com.gcc.app.ui.theme.*
import com.gcc.app.ui.utils.displayLabel
import com.gcc.app.viewmodel.RouteScanUiState
import com.gcc.shared.model.HotspotStatus
import com.gcc.shared.model.ScanHotspot
import com.gcc.shared.model.SpotTag

/**
 * Polished scan-complete results screen — animated points counter, stats row,
 * cleaned hotspot summary, trash volume, share button, done button.
 */
@Composable
fun ScanCompleteScreen(
    uiState: RouteScanUiState,
    onDone: () -> Unit,
) {
    val cleanedHotspots = uiState.hotspots.filter { it.status == HotspotStatus.CLEANED }
    val total = uiState.hotspots.size
    val cleaned = cleanedHotspots.size

    val totalVolume = uiState.hotspots.sumOf { it.estimatedVolumeLiters ?: 0.0 }
    val totalWeight = uiState.hotspots.sumOf { it.estimatedWeightLbs ?: 0.0 }
    val hasVolumeData = totalVolume > 0.0 || totalWeight > 0.0

    // Animated points counter: counts from 0 → pointsAwarded over 1.2 s
    var targetPoints by remember { mutableStateOf(0) }
    LaunchedEffect(uiState.pointsAwarded) {
        targetPoints = uiState.pointsAwarded
    }
    val animatedPoints by animateIntAsState(
        targetValue = targetPoints,
        animationSpec = tween(durationMillis = 1200),
        label = "pointsCounter",
    )

    val shareManager = remember { ShareManager() }

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

            // ── Celebratory header ─────────────────────────────────────────
            item {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // Gradient trophy badge
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .clip(RoundedCornerShape(48.dp))
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(GccGoldLight, GccGold, GccGoldDark),
                                )
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = "\uD83C\uDFC6", fontSize = 44.sp)
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Scan Complete!",
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
                        color = GccGreen,
                        textAlign = TextAlign.Center,
                    )

                    uiState.route?.displayName?.let { name ->
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMuted,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            // ── Animated points card ────────────────────────────────────────
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(GccGoldDark, GccGold, GccGoldLight),
                            )
                        )
                        .padding(vertical = 24.dp, horizontal = 20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "+$animatedPoints",
                            style = MaterialTheme.typography.displayMedium.copy(
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 56.sp,
                            ),
                            color = GccSurface,
                        )
                        Text(
                            text = "Points Earned",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = GccSurface.copy(alpha = 0.85f),
                        )
                    }
                }
            }

            // ── Stats cards row ──────────────────────────────────────────────
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    StatCard(
                        value = if (total > 0) "$cleaned / $total" else "—",
                        label = "Hotspots",
                        accentColor = GccGreen,
                        modifier = Modifier.weight(1f),
                    )
                    StatCard(
                        value = formatElapsed(uiState.elapsedSeconds),
                        label = "Time",
                        accentColor = GccBlue,
                        modifier = Modifier.weight(1f),
                    )
                    StatCard(
                        value = "${uiState.coveragePercent.toInt()}%",
                        label = "Coverage",
                        accentColor = GccGold,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // ── Cleaned hotspots summary ────────────────────────────────────
            if (cleanedHotspots.isNotEmpty()) {
                item {
                    GccCard(accentColor = CardAccentGreen) {
                        Text(
                            text = "Areas Cleaned",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        cleanedHotspots.forEachIndexed { index, hotspot ->
                            HotspotSummaryRow(hotspot = hotspot, index = index + 1)
                            if (index < cleanedHotspots.lastIndex) {
                                Spacer(modifier = Modifier.height(6.dp))
                            }
                        }
                    }
                }
            }

            // ── Trash volume card ────────────────────────────────────────────
            if (hasVolumeData) {
                item {
                    GccCard(accentColor = CardAccentPurple) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(text = "\uD83D\uDDD1\uFE0F", fontSize = 32.sp)
                            Column {
                                Text(
                                    text = "Trash Removed",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                if (totalVolume > 0.0) {
                                    Text(
                                        text = "Volume: ${formatOneDecimal(totalVolume)} L",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextMuted,
                                    )
                                }
                                if (totalWeight > 0.0) {
                                    Text(
                                        text = "Weight: ${formatOneDecimal(totalWeight)} lbs",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextMuted,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── Share button ─────────────────────────────────────────────────
            item {
                val summary = buildShareSummary(
                    cleaned = cleaned,
                    coveragePercent = uiState.coveragePercent.toInt(),
                    elapsedSeconds = uiState.elapsedSeconds,
                    pointsAwarded = uiState.pointsAwarded,
                )
                GccButton(
                    text = "Share Results",
                    onClick = { shareManager.shareText(summary) },
                    variant = GccButtonVariant.OUTLINED,
                )
            }

            // ── Done button ──────────────────────────────────────────────────
            item {
                GccButton(
                    text = "Done",
                    onClick = onDone,
                    variant = GccButtonVariant.PRIMARY,
                )
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

// ── Private helpers ──────────────────────────────────────────────────────────

@Composable
private fun HotspotSummaryRow(hotspot: ScanHotspot, index: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Index badge
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(GccGreen),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "$index",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = GccSurface,
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            // Trash type labels
            if (hotspot.trashTypes.isNotEmpty()) {
                Text(
                    text = hotspot.trashTypes.joinToString(" · ") { it.displayLabel() },
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = GccOnSurface,
                )
            } else {
                Text(
                    text = "General waste",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = GccOnSurface,
                )
            }

            // Severity stars + optional volume
            val stars = "\u2605".repeat(hotspot.severity.coerceIn(1, 5))
            val suffix = hotspot.estimatedVolumeLiters
                ?.let { " · ${formatOneDecimal(it)} L" } ?: ""
            Text(
                text = "$stars$suffix",
                style = MaterialTheme.typography.labelSmall,
                color = GccGold,
            )
        }
    }
}

private fun buildShareSummary(
    cleaned: Int,
    coveragePercent: Int,
    elapsedSeconds: Long,
    pointsAwarded: Int,
): String {
    val timeStr = formatElapsed(elapsedSeconds)
    return buildString {
        appendLine("\uD83E\uDDF9 Just completed a Scout & Clean route!")
        appendLine("\u2705 $cleaned hotspot${if (cleaned != 1) "s" else ""} cleaned")
        appendLine("\uD83D\uDCCA $coveragePercent% coverage achieved")
        appendLine("\u23F1 $timeStr total time")
        appendLine("\uD83C\uDFC6 $pointsAwarded points earned")
        append("#GlobalCommunityCleaners #ScoutAndClean")
    }
}
