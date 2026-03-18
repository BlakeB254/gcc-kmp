package com.gcc.app.ui.screens

import androidx.compose.runtime.*
import com.gcc.app.viewmodel.RouteScanViewModel
import com.gcc.shared.model.Route
import com.gcc.shared.model.ScanPhase
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
    val uiState by viewModel.uiState.collectAsState()

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
                viewModel.confirmHotspotCleaned(
                    hotspot.id,
                    afterPhotoUrl = "after_${hotspot.id}.jpg",
                    verificationScore = 1.0f,
                )
            },
            onSkipHotspot = { viewModel.skipHotspot(it.id) },
            onBack = onBack,
        )

        ScanPhase.SUBMITTING -> ScanSubmittingScreen()

        ScanPhase.COMPLETE -> ScanCompleteScreen(
            uiState = uiState,
            onDone = onComplete,
        )
    }
}
