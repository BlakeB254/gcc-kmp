package com.gcc.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcc.app.platform.CoverageDeviceId
import com.gcc.app.platform.CoverageEventQueue
import com.gcc.app.platform.DeviceCapabilities
import com.gcc.app.platform.ObjectCoverageTracker
import com.gcc.app.platform.LocationProvider
import com.gcc.app.platform.OutdoorGate
import com.gcc.app.platform.OutdoorState
import com.gcc.app.platform.SpotArOverlay
import com.gcc.app.platform.SpotArOverlayView
import com.gcc.app.platform.TrackingState
import com.gcc.app.platform.coverageTier
import com.gcc.app.platform.getPlatformName
import com.gcc.app.ui.components.GoOutsideOverlay
import com.gcc.app.ui.theme.GccGreenDark
import com.gcc.shared.api.GccApiClient
import com.gcc.shared.geo.GeofenceVerification
import com.gcc.shared.model.Coordinates
import com.gcc.shared.model.ScanCoverageEventRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import org.koin.compose.koinInject

/**
 * Full-screen AR spot-scan guidance: the worker circles a litter item until
 * all angular sectors are captured. The AR camera view replaces the photo
 * capture UI while active (an ARSession and an AVCaptureSession cannot share
 * the camera), so this screen is toggled from [com.gcc.app.ui.components.SpotScanCapture].
 *
 * Orchestrates the pieces the 2026-07-02 plan's Task 6 called for:
 *  - [ObjectCoverageTracker] — angular coverage from GPS fixes around the item
 *  - [SpotArOverlay] — sector-marker ring + guidance arrow + tracking health
 *  - [CoverageEventQueue] — batches spot-mode coverage events to the ledger,
 *    flushed on a 30s timer and on exit, via GccApiClient.submitCoverageEvents.
 */
@Composable
fun SpotArScreen(
    spotLocation: Coordinates,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val locationProvider = koinInject<LocationProvider>()
    val deviceCapabilities = koinInject<DeviceCapabilities>()
    val apiClient = koinInject<GccApiClient>()

    val overlay = remember { SpotArOverlay() }
    val tracker = remember { ObjectCoverageTracker() }
    val queue = remember(apiClient) {
        CoverageEventQueue(submitFn = { apiClient.submitCoverageEvents(it) })
    }
    // Outlives the composition just long enough for the final flush on exit.
    val flushScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    val coveragePercent by tracker.coveragePercent.collectAsState()
    val trackingState by overlay.trackingState.collectAsState()

    // "Go outside" gate: GPS accuracy + camera scene votes; INDOOR suppresses
    // coverage accrual and shows the scrim.
    val gate = remember { OutdoorGate() }
    val outdoorState by gate.state.collectAsState()
    val isIndoor = outdoorState == OutdoorState.INDOOR

    DisposableEffect(Unit) {
        tracker.initAroundPoint(spotLocation.lat, spotLocation.lng)
        overlay.setItemLocation(spotLocation.lat, spotLocation.lng)
        overlay.start()
        overlay.setSceneVoteListener { vote ->
            gate.onSceneVote(vote, Clock.System.now().toEpochMilliseconds())
        }
        val startedLocationHere = !locationProvider.isTracking
        if (startedLocationHere) {
            locationProvider.startLocationUpdates()
        }
        onDispose {
            overlay.setSceneVoteListener(null)
            overlay.stop()
            if (startedLocationHere) {
                locationProvider.stopLocationUpdates()
            }
            flushScope.launch { queue.flush() }
        }
    }

    LaunchedEffect(Unit) {
        locationProvider.horizontalAccuracyMeters.collect { accuracy ->
            gate.onAccuracySample(accuracy, Clock.System.now().toEpochMilliseconds())
        }
    }

    // Indoors: pause frame analysis (battery); GPS accuracy drives recovery.
    LaunchedEffect(isIndoor) {
        overlay.setFrameAnalysisEnabled(!isIndoor)
    }

    // Feed GPS fixes into the tracker/overlay and enqueue ledger events.
    LaunchedEffect(Unit) {
        var lastLat: Double? = null
        var lastLng: Double? = null
        locationProvider.locationFlow.collect { location ->
            // Keep the AR geo-reference fresh even indoors (harmless), but
            // accrue no coverage while the OutdoorGate reads INDOOR.
            overlay.updateGeoReference(location.lat, location.lng)
            if (gate.state.value == OutdoorState.INDOOR) return@collect

            val distToItem = GeofenceVerification.distanceMeters(
                location.lat, location.lng,
                spotLocation.lat, spotLocation.lng,
            )
            tracker.updateCapturedAngle(location.lat, location.lng, distToItem)
            overlay.updateCoverage(
                tracker.coveredSectors.value,
                tracker.getUncoveredSectorCenters(),
            )

            val segmentMeters = lastLat?.let { prevLat ->
                GeofenceVerification.distanceMeters(prevLat, lastLng!!, location.lat, location.lng)
            } ?: 0.0
            lastLat = location.lat
            lastLng = location.lng

            // Desktop is dev-only and the endpoint rejects its platform value —
            // skip so the queue can't fill with unsendable events.
            if (getPlatformName() != "desktop") queue.enqueue(
                ScanCoverageEventRequest(
                    deviceId = CoverageDeviceId.value,
                    platform = getPlatformName(),
                    lat = location.lat,
                    lng = location.lng,
                    distanceMeters = segmentMeters,
                    scanMode = "spot",
                    deviceTier = deviceCapabilities.deviceClass.coverageTier(),
                    capturedAt = Clock.System.now().toString(),
                )
            )
        }
    }

    // Periodic ledger flush; failed flushes keep events queued for the next tick.
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            queue.flush()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        SpotArOverlayView(overlay = overlay, modifier = Modifier.fillMaxSize())

        // Coverage progress (top-center).
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black.copy(alpha = 0.65f))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (coveragePercent >= 100.0) {
                    "All sides captured — you're done!"
                } else {
                    "Scanned ${coveragePercent.toInt()}% — circle the item"
                },
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
            )
        }

        // Tracking-health recovery prompt (bottom) — degraded tracking only.
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

        // "Go outside" scrim — sits under the exit button so leaving always works.
        GoOutsideOverlay(visible = isIndoor)

        // Exit button (top-left) — returns to the photo-capture view.
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
