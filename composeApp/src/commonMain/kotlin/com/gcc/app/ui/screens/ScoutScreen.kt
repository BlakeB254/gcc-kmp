package com.gcc.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.gcc.app.ui.map.authoredInnerRings
import com.gcc.app.ui.theme.*
import com.gcc.app.viewmodel.RouteScanSectionStatus
import com.gcc.app.viewmodel.RouteScanUiState
import com.gcc.app.viewmodel.RouteScanViewModel
import com.gcc.shared.geo.hasScannableGeometry
import com.gcc.shared.geo.routePathCoordinates
import com.gcc.shared.geo.routeStartCoordinates
import com.gcc.shared.model.Coordinates
import com.gcc.shared.model.DeviceType
import com.gcc.shared.model.RouteStatus

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
    allowUnpublished: Boolean = false,
    isOutsideBoundary: Boolean = false,
    onStartScout: () -> Unit,
    onCompleteScout: () -> Unit,
    onForceCompleteScout: () -> Unit = {},
    onCaptureHotspot: () -> Unit,
    onBeginRoute: () -> Unit = {},
    onBack: () -> Unit,
) {
    // Force-complete confirm: the scout lap must never strand a worker (see
    // completeScout(force=…)); when the normal gate isn't met, the primary
    // button routes through this confirm instead of being disabled.
    var showForceConfirm by remember { mutableStateOf(false) }

    // Build Coordinates from flat lat/lng in uiState
    val currentLocation = if (uiState.currentLat != 0.0 || uiState.currentLng != 0.0) {
        Coordinates(lat = uiState.currentLat, lng = uiState.currentLng)
    } else null

    val routePath = remember(uiState.route) {
        uiState.route?.routePathCoordinates().orEmpty()
    }
    val routeCoords = remember(routePath) {
        routePath.takeIf { it.isNotEmpty() }?.let { listOf(it) }.orEmpty()
    }

    // Hotspot bounty locations for red dot markers
    val hotspotLocations = remember(uiState.hotspots) {
        uiState.hotspots.map { Coordinates(lat = it.lat, lng = it.lng) }
    }

    // Authored per-building inner-exclusion islands; the synthetic 8m inset
    // may only render when the block has none.
    val authoredInnerRings = remember(uiState.route) {
        uiState.route?.innerBoundary.authoredInnerRings()
    }

    // Map centre: user position if available, otherwise route start, otherwise Chicago default
    val mapCenterLat = currentLocation?.lat
        ?: uiState.route?.routeStartCoordinates()?.lat
        ?: MapStyles.NORTH_LAWNDALE_CENTER_LAT
    val mapCenterLng = currentLocation?.lng
        ?: uiState.route?.routeStartCoordinates()?.lng
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
            innerBoundaryRings = authoredInnerRings,
            innerBoundaryFallback = authoredInnerRings.isEmpty(),
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

        // ── Lap progress (top-center) — the persistent "where am I in the
        // 2-lap flow" marker; previously the lap number only appeared in
        // transient AR-overlay captions. ─────────────────────────────────
        LapProgressChip(
            phase = uiState.phase,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 56.dp),
        )

        // ── Bottom panel: coverage bar + loop status + action buttons ─
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.55f)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
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

            if (uiState.scanId != null) {
                SectionScanPromptCard(
                    uiState = uiState,
                    onCaptureHotspot = onCaptureHotspot,
                )
            }

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
                            .background(if (uiState.isLoopClosed) GccGreen else GccSky)
                    )
                    Text(
                        text = if (uiState.isLoopClosed) "Loop closed" else "Return to start to close loop",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (uiState.isLoopClosed) GccGreen else GccSky,
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
                        enabled = uiState.route?.let { route ->
                            (route.status == RouteStatus.PUBLISHED || allowUnpublished) && route.hasScannableGeometry()
                        } == true,
                    )
                }
                else -> {
                    val allSectionsRecorded = uiState.routeSections.isNotEmpty() &&
                        uiState.routeSections.none { it.status == RouteScanSectionStatus.PENDING }
                    // Mirror RouteScanViewModel.canCompleteScout() exactly (incl.
                    // the >=95% escape the button previously dropped).
                    val canComplete = uiState.coveragePercent >= RouteScanViewModel.LAP_COMPLETE_PERCENT ||
                        (uiState.coveragePercent >= 85.0 && uiState.isLoopClosed) ||
                        allSectionsRecorded
                    // Always tappable \u2014 a worker must never be stranded in the
                    // scout lap. When the gate is met it completes normally; when
                    // not, it routes through a force-complete confirm.
                    GccButton(
                        text = if (canComplete) "Complete Scout" else "Complete Scout anyway",
                        onClick = { if (canComplete) onCompleteScout() else showForceConfirm = true },
                        variant = GccButtonVariant.PRIMARY,
                        enabled = true,
                    )
                    if (!canComplete) {
                        Text(
                            text = "Scouting\u2026 ${uiState.coveragePercent.toInt()}% covered",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            // Begin Route: enter the full-screen AR guidance overlay. AR-tier only
            // (Android/desktop have no on-screen AR), and disabled while the worker
            // is outside their assigned block boundary.
            if (uiState.scanId != null && uiState.deviceType == DeviceType.AR) {
                GccButton(
                    text = if (isOutsideBoundary) "Return to boundary to begin route"
                    else "Begin Route",
                    onClick = onBeginRoute,
                    variant = GccButtonVariant.SECONDARY,
                    enabled = !isOutsideBoundary,
                )
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

    // Force-complete confirm: lets the worker leave the scout lap even when
    // coverage stalled below the thresholds and sections couldn't be recorded,
    // so a route can always be finished. Coverage collected so far still uploads.
    if (showForceConfirm) {
        AlertDialog(
            onDismissRequest = { showForceConfirm = false },
            title = { Text("Complete scout anyway?") },
            text = {
                Text(
                    "You haven't fully covered the block (${uiState.coveragePercent.toInt()}%). " +
                        "You can still finish scouting and move on to cleaning — the coverage " +
                        "you've captured is saved.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showForceConfirm = false
                    onForceCompleteScout()
                }) { Text("Complete anyway") }
            },
            dismissButton = {
                TextButton(onClick = { showForceConfirm = false }) { Text("Keep scouting") }
            },
        )
    }
}

@Composable
private fun SectionScanPromptCard(
    uiState: RouteScanUiState,
    onCaptureHotspot: () -> Unit,
) {
    val currentSection = uiState.routeSections.getOrNull(uiState.currentSectionIndex)
    val recordedCount = uiState.routeSections.count { it.status != RouteScanSectionStatus.PENDING }
    val total = uiState.routeSections.size
    val prompt = when {
        currentSection == null -> "All sections recorded. Complete the scout lap when coverage is ready."
        uiState.deviceType == DeviceType.AR -> "Scan area ${currentSection.index + 1} of $total, then capture the AI observation."
        else -> "Take the before photo for section ${currentSection.index + 1} of $total."
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = GccGreen.copy(alpha = 0.10f),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = currentSection?.name ?: "Scout lap recorded",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = GccGreenDark,
            )
            Text(
                text = uiState.sectionPrompt ?: prompt,
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
            Text(
                text = "$recordedCount / $total sections recorded",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = GccGreen,
            )
            if (currentSection != null) {
                GccButton(
                    text = if (uiState.deviceType == DeviceType.AR) {
                        "Capture Area Scan"
                    } else {
                        "Capture Before Photo"
                    },
                    onClick = onCaptureHotspot,
                    variant = GccButtonVariant.SECONDARY,
                )
            }
        }
    }
}
