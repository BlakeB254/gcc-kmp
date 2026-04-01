package com.gcc.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.components.*
import com.gcc.app.ui.map.GccMap
import com.gcc.app.ui.map.MapStyles
import com.gcc.app.ui.theme.*
import com.gcc.app.viewmodel.RouteScanUiState
import com.gcc.shared.model.Coordinates
import com.gcc.shared.model.DeviceType

/**
 * Scout lap screen — shown during the SCOUTING phase.
 *
 * Full-screen map with:
 *  - Route polyline centred on user
 *  - GPS location dot with heading cone
 *  - Hotspot markers (red bounty dots) for detected trash
 *  - AI direction banner sliding in from the top
 *  - Minimap stats chip (top-right): coverage % + hotspot count
 *  - Coverage bar + action buttons panel anchored to the bottom
 *  - "Capture" FAB (bottom-right) for manual hotspot detection (F4 placeholder)
 */
@Composable
fun ScoutScreen(
    uiState: RouteScanUiState,
    onStartScout: () -> Unit,
    onCompleteScout: () -> Unit,
    onCaptureHotspot: () -> Unit,
    onBack: () -> Unit,
) {
    // Build Coordinates from flat lat/lng in uiState
    val currentLocation = if (uiState.currentLat != 0.0 || uiState.currentLng != 0.0) {
        Coordinates(lat = uiState.currentLat, lng = uiState.currentLng)
    } else null

    // Route polyline from checkpoint coordinates
    val routeCoords = remember(uiState.route) {
        uiState.route?.checkpoints
            ?.sortedBy { it.sequenceOrder }
            ?.map { Coordinates(lat = it.coordinates.lat, lng = it.coordinates.lng) }
            ?.let { listOf(it) }
            ?: emptyList()
    }

    // Hotspot bounty locations for red dot markers
    val hotspotLocations = remember(uiState.hotspots) {
        uiState.hotspots.map { Coordinates(lat = it.lat, lng = it.lng) }
    }

    // Map centre: user position if available, otherwise route start, otherwise Chicago default
    val mapCenterLat = currentLocation?.lat
        ?: uiState.route?.checkpoints?.firstOrNull()?.coordinates?.lat
        ?: MapStyles.NORTH_LAWNDALE_CENTER_LAT
    val mapCenterLng = currentLocation?.lng
        ?: uiState.route?.checkpoints?.firstOrNull()?.coordinates?.lng
        ?: MapStyles.NORTH_LAWNDALE_CENTER_LNG

    Box(modifier = Modifier.fillMaxSize()) {
        // ── Full-screen map ──────────────────────────────────────────
        GccMap(
            modifier = Modifier.fillMaxSize(),
            centerLat = mapCenterLat,
            centerLng = mapCenterLng,
            zoom = MapStyles.ZOOM_ACTIVE_JOB,
            currentLocation = currentLocation,
            enableGestures = true,
            routeCoordinates = routeCoords,
            bountyLocations = hotspotLocations,
            showBuildings = true,
            showGridLines = true,
            showControls = true,
            statusBarPadding = true,
        )

        // ── AI direction banner (top, slides in) ─────────────────────
        AiDirectionBanner(
            text = uiState.aiDirectionText,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .statusBarsPadding(),
        )

        // ── Minimap stats chip (top-right) ───────────────────────────
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 56.dp, end = 12.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.65f))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            val hotspotsText = if (uiState.hotspots.isNotEmpty()) " | ${uiState.hotspots.size} \uD83D\uDD25" else ""
            Text(
                text = "${uiState.coveragePercent.toInt()}%$hotspotsText",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
            )
        }

        // ── Device type badge (top-left area, below back button) ─────
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(top = 52.dp, start = 12.dp)
                .clip(RoundedCornerShape(50))
                .background(
                    if (uiState.deviceType == DeviceType.AR) GccBlue.copy(alpha = 0.85f)
                    else GccGreen.copy(alpha = 0.85f)
                )
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(
                text = if (uiState.deviceType == DeviceType.AR) "AR" else "GPS",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
            )
        }

        // ── Bottom panel: coverage bar + loop status + action buttons ─
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Route name row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = uiState.route?.displayName ?: "Scout Route",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    )
                    Text(
                        text = uiState.route?.code ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                }

                // Elapsed time
                if (uiState.scanId != null) {
                    val mins = uiState.elapsedSeconds / 60
                    val secs = uiState.elapsedSeconds % 60
                    Text(
                        text = "${mins}:${secs.toString().padStart(2, '0')}",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = TextMuted,
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

            // Hotspot count row
            if (uiState.hotspots.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Hotspots detected",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                    Text(
                        text = "${uiState.hotspots.size}",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = GccError,
                    )
                }
            }

            uiState.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = GccError)
            }

            // Phase action button
            when {
                uiState.isSubmitting -> {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
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
                        text = if (canComplete) "Complete Scout"
                        else "Scouting\u2026 ${uiState.coveragePercent.toInt()}%",
                        onClick = { if (canComplete) onCompleteScout() },
                        variant = GccButtonVariant.PRIMARY,
                        enabled = canComplete,
                    )
                }
            }
        }

        // ── Capture FAB (bottom-right, above bottom panel) ───────────
        if (uiState.scanId != null) {
            FloatingActionButton(
                onClick = {
                    onCaptureHotspot()
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 16.dp, bottom = 180.dp),
                containerColor = GccError,
                contentColor = Color.White,
                shape = CircleShape,
            ) {
                Text(
                    text = "\uD83D\uDCF7",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        // ── Back button (top-left) ────────────────────────────────────
        TextButton(
            onClick = onBack,
            modifier = Modifier
                .statusBarsPadding()
                .padding(8.dp)
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

    // ScoutCaptureDialog is now managed by RouteScanScreen — no stub needed here.
}
