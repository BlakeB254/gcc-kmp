package com.gcc.app.ui.screens

import androidx.compose.runtime.*
import com.gcc.app.ui.components.ScoutCaptureDialog
import com.gcc.app.viewmodel.RouteScanViewModel
import com.gcc.shared.api.GccApiClient
import com.gcc.shared.model.Route
import com.gcc.shared.model.ScanPhase
import com.gcc.shared.model.SpotTag
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Phase router for the Scout & Clean route scan flow.
 *
 * Delegates to the appropriate phase screen based on [ScanPhase]:
 *   SCOUTING       → ScoutScreen
 *   SCOUT_REVIEW   → ScoutReviewScreen
 *   CLEANING       → CleanScreen
 *   SUBMITTING     → ScanSubmittingScreen
 *   COMPLETE       → ScanCompleteScreen
 */
@Composable
fun RouteScanScreen(
    route: Route,
    onBack: () -> Unit = {},
    onComplete: () -> Unit = {},
) {
    val viewModel = koinInject<RouteScanViewModel>()
    val apiClient = koinInject<GccApiClient>()
    val uiState by viewModel.uiState.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    // Scout-phase: whether the ScoutCaptureDialog is open
    var showScoutCapture by remember { mutableStateOf(false) }

    LaunchedEffect(route.id) {
        viewModel.initForRoute(route)
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.onCleared() }
    }

    when (uiState.phase) {
        ScanPhase.SCOUTING -> ScoutScreen(
            uiState = uiState,
            onStartScout = { viewModel.startScout() },
            onCompleteScout = { viewModel.completeScout() },
            onCaptureHotspot = { showScoutCapture = true },
            onBack = onBack,
        )

        ScanPhase.SCOUT_REVIEW -> ScoutReviewScreen(
            uiState = uiState,
            onStartCleaning = { viewModel.startCleaning() },
            onBack = onBack,
        )

        ScanPhase.CLEANING -> CleanScreen(
            uiState = uiState,
            onCompleteScan = { viewModel.completeScan() },
            onCleanHotspot = { hotspot ->
                // Fallback (no capture sheet) — mark cleaned with placeholder
                viewModel.confirmHotspotCleaned(
                    hotspot.id,
                    afterPhotoUrl = "after_${hotspot.id}.jpg",
                    verificationScore = 1.0f,
                )
            },
            onSkipHotspot = { viewModel.skipHotspot(it.id) },
            onConfirmCleaned = { hotspotId, afterPhotoUrl, score ->
                viewModel.confirmHotspotCleaned(hotspotId, afterPhotoUrl, score)
            },
            onBack = onBack,
        )

        ScanPhase.SUBMITTING -> ScanSubmittingScreen()

        ScanPhase.COMPLETE -> ScanCompleteScreen(
            uiState = uiState,
            onDone = onComplete,
        )
    }

    // ── ScoutCaptureDialog (F4/F8) ──────────────────────────────────────
    if (showScoutCapture) {
        ScoutCaptureDialog(
            onCaptured = { photoBytes, analysisResult ->
                showScoutCapture = false
                val state = uiState
                val lat = state.currentLat.takeIf { it != 0.0 }
                    ?: state.route?.checkpoints?.firstOrNull()?.coordinates?.lat
                    ?: 0.0
                val lng = state.currentLng.takeIf { it != 0.0 }
                    ?: state.route?.checkpoints?.firstOrNull()?.coordinates?.lng
                    ?: 0.0

                coroutineScope.launch {
                    // Upload photo bytes and get a real URL; fall back gracefully on failure
                    val beforePhotoUrl = try {
                        val urls = apiClient.uploadSpotPhotos(listOf(photoBytes))
                        urls.firstOrNull() ?: "upload_failed"
                    } catch (_: Exception) {
                        "upload_failed"
                    }

                    viewModel.addDetectedHotspot(
                        lat = lat,
                        lng = lng,
                        trashTypes = analysisResult?.suggestedTags?.map { it } ?: emptyList(),
                        severity = analysisResult?.severity ?: 1,
                        beforePhotoUrl = beforePhotoUrl,
                        confidence = analysisResult?.confidence ?: 0f,
                        estimatedVolumeLiters = null,
                        estimatedWeightLbs = analysisResult?.estimatedWeightLbs,
                    )
                }
            },
            onDismiss = { showScoutCapture = false },
        )
    }
}
