package com.gcc.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.components.GccButton
import com.gcc.app.ui.components.GccButtonVariant
import com.gcc.app.ui.components.GccCard
import com.gcc.app.ui.map.GccMap
import com.gcc.app.ui.map.MapStyles
import com.gcc.app.ui.theme.*
import com.gcc.app.ui.utils.displayLabel
import com.gcc.app.ui.utils.emoji
import com.gcc.app.viewmodel.RouteScanUiState
import com.gcc.shared.geo.CheckpointGenerator
import com.gcc.shared.model.Coordinates
import com.gcc.shared.model.HotspotStatus
import com.gcc.shared.model.ScanHotspot
import com.gcc.shared.model.SpotTag

/**
 * Scout review summary screen — shows coverage, hotspot count, elapsed time.
 * Confirms scout lap before beginning the cleaning phase.
 *
 * Layout:
 *  Top 40% — non-interactive GccMap showing the full route + hotspot markers.
 *  Bottom 60% — scrollable results panel with rounded top corners.
 */
@Composable
fun ScoutReviewScreen(
    uiState: RouteScanUiState,
    onStartCleaning: () -> Unit,
    onBack: () -> Unit,
) {
    val effectiveCheckpoints = remember(uiState.route) {
        uiState.route
            ?.let { CheckpointGenerator.generateIfMissing(it) }
            .orEmpty()
            .sortedBy { it.sequenceOrder }
    }

    // Route polyline from checkpoint coordinates
    val routeCoords = remember(effectiveCheckpoints) {
        effectiveCheckpoints
            .map { Coordinates(lat = it.coordinates.lat, lng = it.coordinates.lng) }
            .let { if (it.isNotEmpty()) listOf(it) else emptyList() }
    }

    // All hotspot locations as bounty (red) dots
    val hotspotLocations = remember(uiState.hotspots) {
        uiState.hotspots.map { Coordinates(lat = it.lat, lng = it.lng) }
    }

    // Map center: midpoint of route checkpoints, otherwise Chicago default
    val mapCenterLat = remember(effectiveCheckpoints) {
        effectiveCheckpoints
            .takeIf { it.isNotEmpty() }
            ?.map { it.coordinates.lat }
            ?.average()
            ?: MapStyles.NORTH_LAWNDALE_CENTER_LAT
    }
    val mapCenterLng = remember(effectiveCheckpoints) {
        effectiveCheckpoints
            .takeIf { it.isNotEmpty() }
            ?.map { it.coordinates.lng }
            ?.average()
            ?: MapStyles.NORTH_LAWNDALE_CENTER_LNG
    }

    // Derived stats
    val cleanupTargets = uiState.hotspots.filter { it.status != HotspotStatus.SKIPPED }
    val hotspotCount = cleanupTargets.size
    val documentedOnly = uiState.hotspots.count { it.status == HotspotStatus.SKIPPED }
    val totalVolumeLiters = cleanupTargets.sumOf { it.estimatedVolumeLiters ?: 0.0 }
    val estimatedCleanMinutes = hotspotCount * 2  // ~2 min per hotspot heuristic

    Box(modifier = Modifier.fillMaxSize()) {

        // ── Top 40%: Map summary ──────────────────────────────────────────────
        GccMap(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.42f),
            centerLat = mapCenterLat,
            centerLng = mapCenterLng,
            zoom = MapStyles.ZOOM_ROUTE,
            enableGestures = false,
            routeCoordinates = routeCoords,
            bountyLocations = hotspotLocations,
            showBuildings = false,
            showGridLines = true,
            showControls = false,
            statusBarPadding = true,
        )

        // ── Bottom 60%: Results panel (scrollable, rounded top) ───────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .fillMaxHeight(0.62f)
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(MaterialTheme.colorScheme.background),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {

                // ── Header ────────────────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "\u2714\ufe0f",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Scout Complete",
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold,
                            ),
                            color = GccGreen,
                        )
                        uiState.route?.displayName?.let { name ->
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                            )
                        }
                    }
                }

                // ── Stats Row: Coverage | Hotspots | Time ─────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Coverage
                    StatMiniCard(
                        value = "${uiState.coveragePercent.toInt()}%",
                        label = "Coverage",
                        color = GccGreen,
                        modifier = Modifier.weight(1f),
                    )
                    // Hotspots
                    StatMiniCard(
                        value = "$hotspotCount",
                        label = if (hotspotCount == 1) "Target" else "Targets",
                        color = if (hotspotCount == 0) TextMuted else GccError,
                        modifier = Modifier.weight(1f),
                    )
                    // Time
                    StatMiniCard(
                        value = formatElapsedShort(uiState.elapsedSeconds),
                        label = "Scout Time",
                        color = GccBlue,
                        modifier = Modifier.weight(1f),
                    )
                }

                // ── Estimated Cleanup ─────────────────────────────────────────
                GccCard(accentColor = CardAccentSky) {
                    Text(
                        text = "Estimated Cleanup",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    ScanSummaryRow(
                        label = "Est. trash volume",
                        value = if (totalVolumeLiters > 0)
                            "~${totalVolumeLiters.toInt()} L"
                        else
                            "Unknown",
                    )
                    ScanSummaryRow(
                        label = "Est. cleanup time",
                        value = if (estimatedCleanMinutes > 0)
                            "~$estimatedCleanMinutes min"
                        else
                            "No cleanup targets",
                    )
                    ScanSummaryRow(
                        label = "Documented only",
                        value = "$documentedOnly",
                    )
                    ScanSummaryRow(
                        label = "Loop closed",
                        value = if (uiState.isLoopClosed) "Yes" else "No",
                        valueColor = if (uiState.isLoopClosed) GccGreen else GccSky,
                    )
                }

                // ── Hotspot Gallery ───────────────────────────────────────────
                if (uiState.hotspots.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Detected Hotspots",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            uiState.hotspots.forEachIndexed { index, hotspot ->
                                HotspotMiniCard(
                                    index = index + 1,
                                    hotspot = hotspot,
                                )
                            }
                        }
                    }
                }

                uiState.error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = GccError,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // ── CTA Buttons ───────────────────────────────────────────────
                Spacer(modifier = Modifier.height(4.dp))

                GccButton(
                    text = if (hotspotCount == 0) "Review Documentation" else "Start Cleaning",
                    onClick = onStartCleaning,
                    variant = GccButtonVariant.PRIMARY,
                    isLoading = uiState.isSubmitting,
                    enabled = !uiState.isSubmitting,
                )

                TextButton(
                    onClick = onBack,
                    enabled = !uiState.isSubmitting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Back to Route",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted,
                    )
                }
            }
        }
    }
}

// ── Private helpers ───────────────────────────────────────────────────────────

/**
 * Compact three-stat card used in the stats row.
 * Displays a large colored value above a small muted label.
 */
@Composable
private fun StatMiniCard(
    value: String,
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    GccCard(
        modifier = modifier,
        accentColor = color,
        elevation = 1.dp,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = color,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Card in the horizontal hotspot gallery.
 * Shows hotspot number, primary trash type label + icon, severity stars, and volume.
 */
@Composable
private fun HotspotMiniCard(
    index: Int,
    hotspot: ScanHotspot,
) {
    val primaryTag = hotspot.trashTypes.firstOrNull()
    val tagLabel = primaryTag?.displayLabel() ?: "Trash"
    val tagIcon = primaryTag?.emoji() ?: "\uD83D\uDDD1\uFE0F"
    val severityStars = "\u2605".repeat(hotspot.severity.coerceIn(1, 5))

    Box(
        modifier = Modifier
            .width(120.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            // subtle red-tinted border effect via padding + background layering
            .padding(1.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(GccError.copy(alpha = 0.06f))
            .padding(10.dp),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Photo placeholder or icon
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(GccError.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = tagIcon,
                    style = MaterialTheme.typography.headlineSmall,
                )
            }

            // Hotspot number
            Text(
                text = "#$index",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = GccError,
            )

            // Trash type label
            Text(
                text = tagLabel,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                textAlign = TextAlign.Center,
                maxLines = 1,
            )

            // Severity stars
            Text(
                text = severityStars,
                style = MaterialTheme.typography.labelSmall,
                color = GccSky,
                textAlign = TextAlign.Center,
            )

            // Volume estimate
            hotspot.estimatedVolumeLiters?.let { vol ->
                Text(
                    text = "~${vol.toInt()} L",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
            }
        }
    }
}

/** Short MM:SS format for the stats row (no hours). */
private fun formatElapsedShort(totalSeconds: Long): String {
    val m = (totalSeconds / 60).toInt()
    val s = (totalSeconds % 60).toInt()
    return "${m}:${s.toString().padStart(2, '0')}"
}
