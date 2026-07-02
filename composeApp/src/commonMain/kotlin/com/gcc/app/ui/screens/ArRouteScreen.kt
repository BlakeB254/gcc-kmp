package com.gcc.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcc.app.platform.ArRouteOverlay
import com.gcc.app.platform.ArRouteOverlayView
import com.gcc.app.platform.TrackingState
import com.gcc.app.ui.theme.GccError
import com.gcc.app.ui.theme.GccGreenDark
import com.gcc.app.viewmodel.RouteScanViewModel

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
    val trackingState by overlay.trackingState.collectAsState()

    // Overlay lifecycle: start when entering the AR screen, stop on the way out.
    // (iOS also pauses the ARSession from the UIKitView's onRelease.)
    DisposableEffect(overlay) {
        overlay.start()
        onDispose { overlay.stop() }
    }

    // Pair every GPS fix with the AR camera position, then refresh the fog so
    // it appears as soon as the first pairing exists (fog placement is a no-op
    // until then — see ArRouteOverlay's geo-reference contract).
    LaunchedEffect(ui.currentLat, ui.currentLng) {
        if (ui.currentLat != 0.0 || ui.currentLng != 0.0) {
            overlay.updateGeoReference(ui.currentLat, ui.currentLng)
            overlay.updateFog(viewModel.unscannedCells()) { row, col ->
                viewModel.cellCenterLatLng(row, col)
            }
        }
    }
    LaunchedEffect(isOutsideBoundary) {
        overlay.updateBoundaryStatus(isOutsideBoundary)
    }
    LaunchedEffect(scannedCells) {
        overlay.updateFog(viewModel.unscannedCells()) { row, col ->
            viewModel.cellCenterLatLng(row, col)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ArRouteOverlayView(overlay = overlay, modifier = Modifier.fillMaxSize())

        // Boundary-crossing warning banner (top).
        if (isOutsideBoundary) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(top = 48.dp, start = 16.dp, end = 16.dp)
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

        // Tracking-health recovery prompt (bottom) — only for degraded tracking.
        // NOT_AVAILABLE means no AR at all (stub targets, session warming up), so
        // no prompt; NORMAL needs none.
        if (trackingState == TrackingState.LIMITED) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 96.dp, start = 16.dp, end = 16.dp)
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

        // Exit AR button (top-left) — returns to the scout map.
        TextButton(
            onClick = onExit,
            modifier = Modifier
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
                Text("✕", style = MaterialTheme.typography.titleMedium, color = GccGreenDark)
            }
        }
    }
}
