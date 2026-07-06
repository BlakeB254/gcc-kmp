package com.gcc.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log2
import com.gcc.app.platform.ArRouteOverlay
import com.gcc.app.platform.ArRouteOverlayView
import com.gcc.app.platform.CoverageTracker
import com.gcc.app.platform.OutdoorState
import com.gcc.app.platform.TrackingState
import com.gcc.app.platform.isGeoRefFixUsable
import com.gcc.app.platform.WorldMarkerKind
import com.gcc.app.platform.DiagnosticsLog
import com.gcc.app.ui.components.ConfirmingOutdoorsChip
import com.gcc.app.ui.components.DetectionReticleOverlay
import com.gcc.app.ui.components.DiagnosticsOverlay
import com.gcc.app.ui.components.GoOutsideOverlay
import com.gcc.app.ui.components.PointsPip
import com.gcc.app.ui.components.PointsPipOverlay
import com.gcc.app.ui.components.ScanMinimapOverlay
import com.gcc.app.ui.components.SessionPointsPill
import com.gcc.app.ui.map.GccMap
import com.gcc.app.ui.map.MapStyles
import com.gcc.app.ui.map.mapOverlayZoomBias
import com.gcc.app.ui.theme.GccError
import com.gcc.app.ui.theme.GccGreen
import com.gcc.app.ui.theme.GccGreenDark
import com.gcc.app.viewmodel.RouteScanViewModel
import com.gcc.shared.model.Coordinates

/**
 * Full-screen AR route guidance overlay, shown during SCOUTING once the worker
 * taps "Begin Route" (see [RouteScanViewModel.beginRoute]). Fully replaces the
 * scout map with the live AR camera view for the duration.
 *
 * Drives [ArRouteOverlay] from the ViewModel's read-only state projections:
 *  - GPS fixes (uiState.currentLat/Lng) → [ArRouteOverlay.updateGeoReference]
 *  - [RouteScanViewModel.isOutsideBoundary] → boundary-crossing veil
 *  - [RouteScanViewModel.unscannedCells] + cellCenterLatLng → fog-of-war
 *  - [ArRouteOverlay.trackingState] → "hold steady" recovery prompt
 *
 * On Android/Desktop [ArRouteOverlayView] is an empty box and the overlay
 * methods are no-ops, so this screen degrades gracefully to just the exit chrome.
 */
@Composable
fun ArRouteScreen(
    viewModel: RouteScanViewModel,
    onExit: () -> Unit = {},
) {
    val overlay = remember { ArRouteOverlay() }
    val ui by viewModel.uiState.collectAsState()
    val isOutsideBoundary by viewModel.isOutsideBoundary.collectAsState()
    val scannedCells by viewModel.scannedCells.collectAsState()
    val fogCellCenters by viewModel.fogCellCenters.collectAsState()
    val staleCellCenters by viewModel.staleCellCenters.collectAsState()
    val freshCellCenters by viewModel.freshCellCenters.collectAsState()
    val trackingState by overlay.trackingState.collectAsState()
    val gpsHeading by viewModel.headingDegrees.collectAsState()
    val outdoorState by viewModel.outdoorState.collectAsState()
    val isIndoor = outdoorState == OutdoorState.INDOOR
    val sessionPoints by viewModel.sessionPointsEstimate.collectAsState()
    val liveDetections by overlay.liveDetections.collectAsState()
    val expectedObservations by viewModel.expectedObservations.collectAsState()
    val nearbyExpected by viewModel.nearbyExpectedObservation.collectAsState()
    val minimapBoundaries by viewModel.minimapBoundaries.collectAsState()
    val (outerRing, innerRings) = minimapBoundaries
    val corridorCenters = remember(outerRing) { viewModel.corridorCellCenters() }

    // Floating "+N pts" pips, fed by the ViewModel's one-shot pip events.
    var pips by remember { mutableStateOf(listOf<PointsPip>()) }
    var nextPipId by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        viewModel.pointsPips.collect { amount ->
            pips = pips + PointsPip(id = nextPipId++, amount = amount)
        }
    }

    // Overlay lifecycle: start when entering the AR screen, stop on the way out.
    // (iOS also pauses the ARSession from the UIKitView's onRelease.)
    // Camera scene votes feed the ViewModel's OutdoorGate while the screen lives.
    DisposableEffect(overlay) {
        overlay.start()
        overlay.setSceneVoteListener { vote -> viewModel.onSceneVote(vote) }
        overlay.setCeilingListener { detected -> viewModel.onCeilingPlane(detected) }
        overlay.setMarkerPlacedListener { lat, lng, label -> viewModel.onMarkerPlaced(lat, lng, label) }
        onDispose {
            overlay.setSceneVoteListener(null)
            overlay.setCeilingListener(null)
            overlay.setMarkerPlacedListener(null)
            overlay.stop()
        }
    }

    // While judged indoors, pause frame analysis (saves battery; the GPS
    // accuracy signal alone drives recovery once the worker steps outside).
    LaunchedEffect(isIndoor) {
        overlay.setFrameAnalysisEnabled(!isIndoor)
    }

    // Fast scene-vote cadence while not yet confirmed OUTDOOR (UNCERTAIN or
    // INDOOR) — faster recovery once the worker actually steps outside.
    LaunchedEffect(outdoorState) {
        overlay.setSceneVoteCadence(fast = outdoorState != OutdoorState.OUTDOOR)
    }

    // Field diagnostics: AR tracking transitions land in the on-screen log.
    LaunchedEffect(trackingState) {
        DiagnosticsLog.info("ar-tracking", "route AR tracking → $trackingState")
    }

    // Pair every GPS fix with the AR camera position, then refresh the fog so
    // it appears as soon as the first pairing exists (fog placement is a no-op
    // until then — see ArRouteOverlay's geo-reference contract). Fixes worse
    // than MAX_GEO_REF_ACCURACY_METERS must not re-anchor the frame: one wild
    // fix would drag every fog/vector/wall node with it.
    LaunchedEffect(ui.currentLat, ui.currentLng) {
        if (ui.currentLat != 0.0 || ui.currentLng != 0.0) {
            // Lattice origin first, geo-ref second: updateGeoReference
            // re-derives the AR-local grid phase, so the origin must already
            // be known when the pairing lands. Repeat sets are idempotent —
            // the route grid may not have initialized on the first fixes.
            viewModel.latticeOrigin()?.let { (lat0, lng0) ->
                overlay.setGridLatticeOrigin(lat0, lng0)
            }
            if (isGeoRefFixUsable(viewModel.gpsAccuracyMeters.value)) {
                overlay.updateGeoReference(ui.currentLat, ui.currentLng)
            }
            overlay.updateFog(viewModel.unscannedCells()) { row, col ->
                viewModel.cellCenterLatLng(row, col)
            }
        }
    }
    LaunchedEffect(isOutsideBoundary) {
        overlay.updateBoundaryStatus(isOutsideBoundary)
    }
    // World-anchored boundary walls: the block outer ring + inner exclusion
    // islands, rendered on the ground so panning toward the edge shows the
    // boundary line in place. No-op until the geo reference pairs; empty
    // outerRing (boundary-less corridor routes) yields no walls.
    LaunchedEffect(outerRing, innerRings) {
        overlay.updateBoundaries(outerRing, innerRings)
    }
    LaunchedEffect(scannedCells) {
        overlay.updateFog(viewModel.unscannedCells()) { row, col ->
            viewModel.cellCenterLatLng(row, col)
        }
        // Positive feedback for credited ground: the same vector-scan quads
        // the env scan draws, so both modes read as "surveyed graph paper".
        overlay.updateScannedCells(
            scannedCells.map { (row, col) -> viewModel.cellCenterLatLng(row, col) },
        )
    }

    // Expected-trash markers from the digital twin. Re-run on GPS ticks:
    // marker placement no-ops until the first geo-reference pairing exists,
    // and ArFrameFeedback's 3m dedupe swallows the repeats afterwards.
    LaunchedEffect(expectedObservations, ui.currentLat, ui.currentLng) {
        expectedObservations.forEach { obs ->
            overlay.addWorldMarker(obs.lat, obs.lng, WorldMarkerKind.EXPECTED_TRASH)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ArRouteOverlayView(overlay = overlay, modifier = Modifier.fillMaxSize())

        // Live trash-detection reticles over the camera feed.
        DetectionReticleOverlay(
            detections = liveDetections,
            modifier = Modifier.fillMaxSize(),
        )

        // Live points HUD (top-right) + floating reward pips.
        SessionPointsPill(
            points = sessionPoints,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 8.dp, end = 12.dp),
        )
        PointsPipOverlay(
            pips = pips,
            onExpired = { id -> pips = pips.filterNot { it.id == id } },
            modifier = Modifier.fillMaxSize(),
        )

        // Top-center stack: lap fill bar, then (when applicable) the
        // boundary-crossing warning below it.
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = 8.dp, start = 56.dp, end = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Lap progress: the required block area's fill percentage. Turns
            // celebratory at LAP_COMPLETE_PERCENT (the lap-1 → lap-2 shift).
            val fillFraction = (ui.coveragePercent / 100.0).toFloat().coerceIn(0f, 1f)
            val lapDone = ui.coveragePercent >= RouteScanViewModel.LAP_COMPLETE_PERCENT
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = when {
                        ui.lapNumber >= 2 -> "Lap 2 · clear the problem areas"
                        lapDone -> "Lap 1 complete!"
                        else -> "Lap 1 · ${ui.coveragePercent.toInt()}% of block scanned"
                    },
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                )
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { fillFraction },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = if (lapDone) GccGreen else GccGreen.copy(alpha = 0.85f),
                    trackColor = Color.White.copy(alpha = 0.25f),
                )
            }

            if (isOutsideBoundary) {
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(GccError.copy(alpha = 0.9f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "You've left the route boundary — head back inside.",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = Color.White,
                    )
                }
            }
        }

        // Tracking-health recovery prompt (bottom) — only for degraded tracking.
        // NOT_AVAILABLE means no AR at all (stub targets, session warming up), so
        // no prompt; NORMAL needs none.
        if (trackingState == TrackingState.LIMITED) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 88.dp, start = 16.dp, end = 16.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Hold steady — improving AR tracking. Move slowly and keep the area lit.",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White,
                )
            }
        }

        // Walking-pace prompt — same slot as the tracking prompt, which wins
        // when both apply (degraded tracking already tells them to slow down).
        if (ui.movingTooFast && trackingState != TrackingState.LIMITED) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 88.dp, start = 16.dp, end = 16.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Slow down — scanning works best at an easy walking pace.",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White,
                )
            }
        }

        // Expected-observation confirm sheet (rectify flow).
        nearbyExpected?.let { obs ->
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 76.dp, start = 16.dp, end = 16.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black.copy(alpha = 0.8f))
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Trash was reported here — is it still there?",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color.White.copy(alpha = 0.15f))
                            .clickable { viewModel.dismissExpectedObservation(obs) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = "Still there",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White,
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(GccGreen)
                            .clickable { viewModel.rectifyObservation(obs) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = "Already clean · +25 pts",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            color = Color.White,
                        )
                    }
                }
            }
        }

        // Back to the scout map (bottom-right) — coverage keeps accruing there.
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 16.dp, bottom = 16.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(GccGreen)
                .clickable { onExit() }
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Text(
                text = "Back to Map",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }

        // Live minimap — fog (never scanned) / stale (re-visit) / this-session
        // scanned cells + hotspot pins, matching the spot-capture minimap.
        // Suppressed while the rectify confirm sheet is showing: the sheet's
        // buttons (below) would otherwise be covered by this BottomStart map,
        // since the minimap is drawn later and wins z-order.
        if (nearbyExpected == null && (ui.currentLat != 0.0 || ui.currentLng != 0.0)) {
            val miniMapWidth: Dp = 160.dp
            val miniMapHeight: Dp = miniMapWidth * 0.78f

            // Frame the whole block when boundaries exist: the minimap's job
            // during a block lap is showing the block filling in, not chasing
            // the worker. Falls back to tracking the worker on boundary-less
            // routes.
            val density = LocalDensity.current
            val blockFrame = remember(outerRing, density) {
                if (outerRing.size < 3) return@remember null
                val minLat = outerRing.minOf { it.first }
                val maxLat = outerRing.maxOf { it.first }
                val minLng = outerRing.minOf { it.second }
                val maxLng = outerRing.maxOf { it.second }
                val centerLat = (minLat + maxLat) / 2.0
                val centerLng = (minLng + maxLng) / 2.0
                val spanNorthMeters = (maxLat - minLat) * 111_320.0
                val spanEastMeters = (maxLng - minLng) * 111_320.0 * cos(centerLat * PI / 180.0)
                val boxPx = with(density) { minOf(miniMapWidth.toPx(), miniMapHeight.toPx()) }
                // Invert metersToPixels: find z where the padded span fits the box,
                // then shift into MapLibre point-space (boxPx is PHYSICAL pixels)
                // so the basemap frames the same extent the overlay draws.
                val spanMeters = maxOf(spanNorthMeters, spanEastMeters) * 1.2
                val zoom = log2(156543.03392 * cos(centerLat * PI / 180.0) * boxPx / spanMeters) -
                    mapOverlayZoomBias(density.density)
                Triple(centerLat, centerLng, zoom)
            }
            val mapCenterLat = blockFrame?.first ?: ui.currentLat
            val mapCenterLng = blockFrame?.second ?: ui.currentLng
            val mapZoom = blockFrame?.third ?: MapStyles.ZOOM_ACTIVE_JOB

            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(start = 12.dp, bottom = 16.dp)
                    .size(width = miniMapWidth, height = miniMapHeight)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.3f)),
            ) {
                GccMap(
                    modifier = Modifier.fillMaxSize(),
                    centerLat = mapCenterLat,
                    centerLng = mapCenterLng,
                    zoom = mapZoom,
                    currentLocation = Coordinates(lat = ui.currentLat, lng = ui.currentLng),
                    gpsHeading = gpsHeading,
                )
                ScanMinimapOverlay(
                    centerLat = mapCenterLat,
                    centerLng = mapCenterLng,
                    zoom = mapZoom,
                    scanCellCenters = scannedCells.map { (row, col) -> viewModel.cellCenterLatLng(row, col) },
                    cellSizeMeters = CoverageTracker.cellSizeMeters,
                    trashItems = ui.hotspots.map { Coordinates(lat = it.lat, lng = it.lng) },
                    fogCellCenters = fogCellCenters,
                    staleCellCenters = staleCellCenters,
                    freshCellCenters = freshCellCenters,
                    outerBoundary = outerRing,
                    innerBoundaries = innerRings,
                    corridorCellCenters = corridorCenters,
                    guidanceTarget = ui.guidanceTarget?.center,
                )
            }
        }

        // "Go outside" scrim — sits under the exit button so leaving AR always works.
        GoOutsideOverlay(visible = isIndoor, onImOutside = { viewModel.forceOutdoors() })
        ConfirmingOutdoorsChip(
            visible = outdoorState == OutdoorState.UNCERTAIN,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 18.dp),
        )

        DiagnosticsOverlay()

        // Exit AR button (top-left) — returns to the scout map.
        TextButton(
            onClick = onExit,
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
                Text("✕", style = MaterialTheme.typography.titleMedium, color = GccGreenDark)
            }
        }
    }
}
