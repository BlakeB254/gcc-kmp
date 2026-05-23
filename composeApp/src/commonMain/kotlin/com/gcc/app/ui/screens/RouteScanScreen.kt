package com.gcc.app.ui.screens

import androidx.compose.runtime.*
import com.gcc.app.platform.CapturedPhoto
import com.gcc.app.ui.components.ScoutCaptureDialog
import com.gcc.app.viewmodel.RouteScanViewModel
import com.gcc.shared.api.GccApiClient
import com.gcc.shared.geo.CheckpointGenerator
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
    onBack: () -> Unit = {},
    onComplete: () -> Unit = {},
) {
    val viewModel = koinInject<RouteScanViewModel>()
    val apiClient = koinInject<GccApiClient>()
    val uiState by viewModel.uiState.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    // Scout-phase: whether the ScoutCaptureDialog is open
    var showScoutCapture by remember { mutableStateOf(false) }

    LaunchedEffect(route.id, allowUnpublished) {
        viewModel.initForRoute(route, allowUnpublished = allowUnpublished)
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.onCleared() }
    }

    when (uiState.phase) {
        ScanPhase.SCOUTING -> ScoutScreen(
            uiState = uiState,
            allowUnpublished = allowUnpublished,
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
            onCaptured = { photo, analysisResult, cleanupTarget ->
                showScoutCapture = false
                val state = uiState
                val effectiveCheckpoints = state.route
                    ?.let { CheckpointGenerator.generateIfMissing(it) }
                    .orEmpty()
                    .sortedBy { it.sequenceOrder }
                val sectionIndex = state.currentSectionIndex
                    .takeIf { it in state.routeSections.indices }
                val activeSection = sectionIndex?.let { state.routeSections[it] }
                val lat = state.currentLat.takeIf { it != 0.0 }
                    ?: activeSection?.coordinates?.lat
                    ?: effectiveCheckpoints.getOrNull(sectionIndex ?: 0)?.coordinates?.lat
                    ?: 0.0
                val lng = state.currentLng.takeIf { it != 0.0 }
                    ?: activeSection?.coordinates?.lng
                    ?: effectiveCheckpoints.getOrNull(sectionIndex ?: 0)?.coordinates?.lng
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
                        "manual_before_${sectionIndex ?: 0}.jpg"
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
