package com.gcc.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.gcc.shared.geo.GeofenceVerification
import com.gcc.shared.model.HotspotStatus
import com.gcc.shared.model.ScanHotspot

/**
 * Cleaning lap screen — shown during the CLEANING phase.
 *
 * Displays progress, ProximityAlertCard for nearby hotspots,
 * the full hotspot list with status badges, and the Complete Scan button.
 */
@Composable
fun CleanScreen(
    uiState: RouteScanUiState,
    onCompleteScan: () -> Unit,
    onCleanHotspot: (ScanHotspot) -> Unit,
    onSkipHotspot: (ScanHotspot) -> Unit,
    onBack: () -> Unit,
) {
    val cleaned = uiState.hotspots.count { it.status == HotspotStatus.CLEANED }
    val skipped = uiState.hotspots.count { it.status == HotspotStatus.SKIPPED }
    val total = uiState.hotspots.size
    val allDone = total == 0 || uiState.hotspots.none { it.status == HotspotStatus.DETECTED }

    val nearbyDist = uiState.nearbyHotspot?.let { h ->
        GeofenceVerification.distanceMeters(uiState.currentLat, uiState.currentLng, h.lat, h.lng)
    }

    Scaffold(contentWindowInsets = WindowInsets(0)) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
                    .padding(top = 16.dp, bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Clean Lap",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    )
                }

                // Progress counter
                item {
                    GccCard(accentColor = CardAccentGreen) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Progress",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextMuted,
                            )
                            Text(
                                text = "$cleaned / $total cleaned",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = GccGreen,
                            )
                        }
                        if (skipped > 0) {
                            Text(
                                text = "$skipped skipped",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                            )
                        }
                    }
                }

                uiState.error?.let {
                    item {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = GccError)
                    }
                }

                // Hotspot list
                if (uiState.hotspots.isNotEmpty()) {
                    item {
                        Text(
                            text = "Hotspots",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = TextMuted,
                        )
                    }
                    items(uiState.hotspots, key = { it.id }) { hotspot ->
                        HotspotListItem(hotspot = hotspot)
                    }
                }

                // Complete scan button
                if (allDone) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        GccButton(
                            text = "Complete Scan",
                            onClick = onCompleteScan,
                            variant = GccButtonVariant.PRIMARY,
                            isLoading = uiState.isSubmitting,
                        )
                    }
                }
            }

            // Proximity alert docked to bottom
            ProximityAlertCard(
                hotspot = uiState.nearbyHotspot,
                distanceMeters = nearbyDist,
                onClean = onCleanHotspot,
                onSkip = onSkipHotspot,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun HotspotListItem(hotspot: ScanHotspot) {
    val (statusText, statusColor) = when (hotspot.status) {
        HotspotStatus.DETECTED -> "Pending" to GccGold
        HotspotStatus.CLEANED -> "Cleaned" to GccGreen
        HotspotStatus.SKIPPED -> "Skipped" to TextMuted
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            val typesLabel = if (hotspot.trashTypes.isNotEmpty())
                hotspot.trashTypes.take(2).joinToString(", ") {
                    it.name.lowercase().replaceFirstChar { c -> c.uppercase() }
                }
            else "Hotspot"
            Text(
                text = typesLabel,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
                text = "Severity ${"★".repeat(hotspot.severity.coerceIn(1, 5))}",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(statusColor.copy(alpha = 0.15f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = statusColor,
            )
        }
    }
}
