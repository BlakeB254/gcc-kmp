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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.components.*
import com.gcc.app.ui.map.GccMap
import com.gcc.app.ui.map.MapStyles
import com.gcc.app.ui.map.mapOverlayZoomBias
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log2

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
    viewModel: RouteScanViewModel,
    uiState: RouteScanUiState,
    allowUnpublished: Boolean = false,
    isOutsideBoundary: Boolean = false,
    onStartScout: () -> Unit,
    onCompleteScout: () -> Unit,
    onCaptureHotspot: () -> Unit,
    onBeginRoute: () -> Unit = {},
    onBack: () -> Unit,
) {
    val scannedCells by viewModel.scannedCells.collectAsState()
    val creditedCells by viewModel.creditedCells.collectAsState()
    val seenCells by viewModel.seenCells.collectAsState()
    val staleCellCenters by viewModel.staleCellCenters.collectAsState()
    val freshCellCenters by viewModel.freshCellCenters.collectAsState()
    val minimapBoundaries by viewModel.minimapBoundaries.collectAsState()
    val visibilityBeam by viewModel.visibilityBeam.collectAsState()
    val gpsHeading by viewModel.headingDegrees.collectAsState()
    val (outerRing, innerRings) = minimapBoundaries
    val corridorCenters = remember(outerRing, innerRings) { viewModel.corridorCellCenters() }
    val scanCellCenters = remember(scannedCells) {
        scannedCells.map { (row, col) -> viewModel.cellCenterLatLng(row, col) }
    }
    val closeCellCenters = remember(creditedCells) {
        creditedCells
            .filterValues { it.quality == com.gcc.app.platform.CoverageQuality.CLOSE }
            .keys
            .map { (row, col) -> viewModel.cellCenterLatLng(row, col) }
    }
    val seenCellCenters = remember(seenCells) {
        seenCells.map { (row, col) -> viewModel.cellCenterLatLng(row, col) }
    }
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

        // Assignment-focused minimap: unlike the full-screen map (which
        // follows the worker), this camera stays fitted to the complete outer
        // boundary so unseen corridor and excluded inner islands remain
        // visible throughout Lap 1.
        if (outerRing.size >= 3 && uiState.scanId != null) {
            val density = LocalDensity.current
            val miniMapWidth: Dp = 196.dp
            val miniMapHeight: Dp = 150.dp
            val blockFrame = remember(outerRing, density) {
                val minLat = outerRing.minOf { it.first }
                val maxLat = outerRing.maxOf { it.first }
                val minLng = outerRing.minOf { it.second }
                val maxLng = outerRing.maxOf { it.second }
                val centerLat = (minLat + maxLat) / 2.0
                val centerLng = (minLng + maxLng) / 2.0
                val spanNorthMeters = (maxLat - minLat) * 111_320.0
                val spanEastMeters = (maxLng - minLng) * 111_320.0 * cos(centerLat * PI / 180.0)
                val boxPx = with(density) { minOf(miniMapWidth.toPx(), miniMapHeight.toPx()) }
                val spanMeters = maxOf(spanNorthMeters, spanEastMeters).coerceAtLeast(12.0) * 1.2
                val zoom = log2(156543.03392 * cos(centerLat * PI / 180.0) * boxPx / spanMeters) -
                    mapOverlayZoomBias(density.density)
                Triple(centerLat, centerLng, zoom)
            }
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 94.dp, end = 12.dp),
                horizontalAlignment = Alignment.End,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = miniMapWidth, height = miniMapHeight)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.32f)),
                ) {
                    GccMap(
                        modifier = Modifier.fillMaxSize(),
                        centerLat = blockFrame.first,
                        centerLng = blockFrame.second,
                        zoom = blockFrame.third,
                        currentLocation = null,
                        gpsHeading = null,
                        enableGestures = false,
                        showControls = false,
                    )
                    ScanMinimapOverlay(
                        centerLat = blockFrame.first,
                        centerLng = blockFrame.second,
                        zoom = blockFrame.third,
                        userLat = uiState.currentLat,
                        userLng = uiState.currentLng,
                        scanCellCenters = scanCellCenters,
                        closeCellCenters = closeCellCenters,
                        seenCellCenters = seenCellCenters,
                        cellSizeMeters = com.gcc.app.platform.CoverageTracker.cellSizeMeters,
                        trashItems = hotspotLocations,
                        staleCellCenters = staleCellCenters,
                        freshCellCenters = freshCellCenters,
                        outerBoundary = outerRing,
                        innerBoundaries = innerRings,
                        corridorCellCenters = corridorCenters,
                        visibilityBeam = visibilityBeam,
                        headingDegrees = gpsHeading,
                    )
                }
                Text(
                    text = "DARK = NEEDS SCAN · GREEN = SCANNED",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.68f))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }
        }

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

        // ── Lap progress + lap clock (top-center) — the persistent "where
        // am I in the 2-lap flow" marker; block assignments are timed work.
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            LapProgressChip(
                phase = uiState.phase,
                lapElapsedSeconds = uiState.elapsedSeconds - uiState.lapStartedAtElapsedSeconds,
            )
            // Lap-1 tally: what the documentation lap has found so far.
            if (uiState.hotspots.isNotEmpty()) {
                val foundLbs = uiState.hotspots.sumOf { it.estimatedWeightLbs ?: 0.0 }
                StatusChip(
                    text = "${uiState.hotspots.size} found" +
                        if (foundLbs > 0) " · ≈${(foundLbs * 10).toInt() / 10.0} lbs" else "",
                    dotColor = Color.White,
                    backgroundColor = Color.Black.copy(alpha = 0.55f),
                    emoji = "🗑",
                )
            }
        }

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
                label = "BLOCK COVERAGE · ${RouteScanViewModel.LAP_COMPLETE_PERCENT.toInt()}% REQUIRED",
                modifier = Modifier.fillMaxWidth(),
            )
            if (uiState.deviceType == DeviceType.AR) {
                CoverageBar(
                    percent = uiState.closeCoveragePercent,
                    label = "CLOSE DETAIL · ${RouteScanViewModel.LAP_CLOSE_COMPLETE_PERCENT.toInt()}% REQUIRED",
                    modifier = Modifier.fillMaxWidth(),
                )
            }

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
                    val closeReady = uiState.deviceType != DeviceType.AR ||
                        uiState.closeCoveragePercent >= RouteScanViewModel.LAP_CLOSE_COMPLETE_PERCENT
                    val captureReady = uiState.deviceType != DeviceType.AR ||
                        uiState.reconstructionCaptureReady
                    val canComplete =
                        uiState.coveragePercent >= RouteScanViewModel.LAP_COMPLETE_PERCENT &&
                            closeReady &&
                            captureReady &&
                            uiState.isLoopClosed
                    GccButton(
                        text = if (canComplete) "Review Lap 1" else "Finish the full scan to continue",
                        onClick = onCompleteScout,
                        variant = GccButtonVariant.PRIMARY,
                        enabled = canComplete,
                    )
                    if (!canComplete) {
                        Text(
                            text = when {
                                uiState.coveragePercent < RouteScanViewModel.LAP_COMPLETE_PERCENT ->
                                    "Scan the remaining shaded ground · ${uiState.coveragePercent.toInt()}% covered"
                                !closeReady ->
                                    "Move closer to light-green areas · ${uiState.closeCoveragePercent.toInt()}% close detail"
                                !captureReady ->
                                    "Open AR guidance and wait for REC to start the 3D world-twin capture"
                                else -> "Return to your starting point to close the loop"
                            },
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
