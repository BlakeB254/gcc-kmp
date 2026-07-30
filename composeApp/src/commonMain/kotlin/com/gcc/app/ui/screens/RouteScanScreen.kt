package com.gcc.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcc.app.platform.CapturedPhoto
import com.gcc.app.ui.components.ScoutCaptureDialog
import com.gcc.app.ui.theme.GccSky
import com.gcc.app.viewmodel.RouteScanViewModel
import com.gcc.shared.api.GccApiClient
import com.gcc.shared.geo.routePathCoordinates
import com.gcc.shared.model.Route
import com.gcc.shared.model.ScanPhase
import com.gcc.shared.serialization.GccJson
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
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
    allowUnpublished: Boolean = false,
    onBehalfOfWorkerId: Int? = null,
    onBehalfOfWorkerName: String? = null,
    completeJobId: Int? = null,
    onBack: () -> Unit = {},
    onComplete: () -> Unit = {},
) {
    val viewModel = koinInject<RouteScanViewModel>()
    val apiClient = koinInject<GccApiClient>()
    val uiState by viewModel.uiState.collectAsState()
    val isOutsideBoundary by viewModel.isOutsideBoundary.collectAsState()
    val lap2WeightStats by viewModel.lap2WeightStats.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    // Scout-phase: whether the ScoutCaptureDialog is open
    var showScoutCapture by remember { mutableStateOf(false) }

    LaunchedEffect(route.id, allowUnpublished) {
        viewModel.initForRoute(
            route,
            allowUnpublished = allowUnpublished,
            onBehalfOfWorkerId = onBehalfOfWorkerId,
            completeJobId = completeJobId,
        )
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.onCleared() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (uiState.phase) {
            ScanPhase.SCOUTING -> {
                // During SCOUTING the worker can tap "Begin Route" to enter the
                // full-screen AR guidance overlay, which fully replaces the scout map.
                if (uiState.showArRoute) {
                    ArRouteScreen(
                        viewModel = viewModel,
                        onExit = { viewModel.exitArRoute() },
                    )
                } else {
                    ScoutScreen(
                        viewModel = viewModel,
                        uiState = uiState,
                        allowUnpublished = allowUnpublished,
                        isOutsideBoundary = isOutsideBoundary,
                        onStartScout = { viewModel.startScout() },
                        onCompleteScout = { viewModel.completeScout() },
                        onCaptureHotspot = { showScoutCapture = true },
                        onBeginRoute = { viewModel.beginRoute() },
                        onBack = onBack,
                    )
                }
            }

            ScanPhase.SCOUT_REVIEW -> ScoutReviewScreen(
                uiState = uiState,
                onStartCleaning = { viewModel.startCleaning() },
                onBack = onBack,
            )

            ScanPhase.CLEANING -> CleanScreen(
                uiState = uiState,
                lap2Weight = lap2WeightStats,
                onCompleteScan = { viewModel.completeScan() },
                onCleanHotspot = { hotspot ->
                    viewModel.setError("Capture an after photo before marking hotspot #${hotspot.id} clean.")
                },
                onSkipHotspot = { viewModel.skipHotspot(it.id) },
                onConfirmCleaned = { hotspotId, afterPhotoUrl, score ->
                    viewModel.confirmHotspotCleaned(hotspotId, afterPhotoUrl, score)
                },
                onBack = onBack,
            )

            ScanPhase.SUBMITTING -> ScanSubmittingScreen(
                submitError = uiState.submitError,
                onRetry = { viewModel.retrySubmit() },
                onBack = onBack,
            )

            ScanPhase.COMPLETE -> ScanCompleteScreen(
                uiState = uiState,
                completeJobId = completeJobId,
                onDone = onComplete,
                onRetryCompletionReport = { viewModel.retryCompletionReportFetch() },
            )
        }


        // Admin proxy banner — shown only when performing on behalf of a verified worker
        if (onBehalfOfWorkerName != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(GccSky.copy(alpha = 0.93f))
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .align(Alignment.TopCenter),
            ) {
                Text(
                    text = "Performing as $onBehalfOfWorkerName (admin)",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }

    // ── ScoutCaptureDialog (F4/F8) ──────────────────────────────────────
    if (showScoutCapture) {
        ScoutCaptureDialog(
            onCaptured = { photo, analysisResult, cleanupTarget ->
                showScoutCapture = false
                val state = uiState
                val routePath = state.route?.routePathCoordinates().orEmpty()
                val sectionIndex = state.currentSectionIndex
                    .takeIf { it in state.routeSections.indices }
                val activeSection = sectionIndex?.let { state.routeSections[it] }
                val lat = state.currentLat.takeIf { it != 0.0 }
                    ?: activeSection?.coordinates?.lat
                    ?: routePath.getOrNull(sectionIndex ?: 0)?.lat
                    ?: 0.0
                val lng = state.currentLng.takeIf { it != 0.0 }
                    ?: activeSection?.coordinates?.lng
                    ?: routePath.getOrNull(sectionIndex ?: 0)?.lng
                    ?: 0.0

                coroutineScope.launch {
                    val beforePhotoUrl = if (photo.bytes.isNotEmpty()) {
                        try {
                            val urls = apiClient.uploadSpotPhotos(listOf(photo.bytes))
                            urls.firstOrNull() ?: throw IllegalStateException("No upload URL returned")
                        } catch (e: Exception) {
                            viewModel.setError("Photo upload failed: ${e.message}")
                            return@launch
                        }
                    } else {
                        // beforePhotoUrl is a required, non-nullable field the
                        // whole way through (this viewmodel param, the server's
                        // 400-validation, the DB NOT NULL column) — it can't be
                        // sent null/omitted. The literal that used to go here,
                        // "manual_before_N.jpg", was never actually uploaded
                        // anywhere and left an unresolvable image reference on
                        // the hotspot. Skip persisting a hotspot with no real
                        // photo instead of recording one with a broken link.
                        viewModel.setError("No photo captured — hotspot not saved")
                        return@launch
                    }

                    viewModel.addDetectedHotspot(
                        lat = lat,
                        lng = lng,
                        trashTypes = analysisResult?.suggestedTags?.map { it } ?: emptyList(),
                        severity = analysisResult?.severity ?: 1,
                        beforePhotoUrl = beforePhotoUrl,
                        confidence = analysisResult?.confidence ?: 0f,
                        estimatedVolumeLiters = photo.depthAnalysis?.estimatedVolumeCubicFt
                            ?.let { it * CUBIC_FEET_TO_LITERS },
                        estimatedWeightLbs = analysisResult?.estimatedWeightLbs,
                        depthData = routeScanEvidenceJson(photo),
                        cleanupTarget = cleanupTarget,
                        sectionIndex = sectionIndex,
                    )
                }
            },
            onDismiss = { showScoutCapture = false },
        )
    }
}

private const val CUBIC_FEET_TO_LITERS = 28.316846592

private fun routeScanEvidenceJson(photo: CapturedPhoto): String? {
    if (photo.depthAnalysis == null && photo.attestation == null) return null
    return buildJsonObject {
        photo.depthAnalysis?.let { put("depthAnalysis", GccJson.encodeToJsonElement(it)) }
        photo.attestation?.let { put("captureAttestation", GccJson.encodeToJsonElement(it)) }
    }.toString()
}
