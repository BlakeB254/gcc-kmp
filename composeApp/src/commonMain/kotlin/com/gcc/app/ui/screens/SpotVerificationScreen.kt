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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gcc.app.platform.LocationProvider
import com.gcc.app.ui.components.*
import com.gcc.app.ui.theme.*
import com.gcc.app.viewmodel.SpotVerificationViewModel
import com.gcc.app.viewmodel.VerificationPhase
import com.gcc.shared.model.Coordinates
import com.gcc.shared.model.ScanCapturedPhoto
import com.gcc.shared.model.SpotVerificationResult
import kotlinx.datetime.Clock
import org.koin.compose.koinInject

@Composable
fun SpotVerificationScreen(
    reportId: Int = 0,
    onBack: () -> Unit = {},
) {
    val viewModel = koinInject<SpotVerificationViewModel>()
    val locationProvider = koinInject<LocationProvider>()
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(reportId) {
        viewModel.initForReport(reportId)
    }

    // Feed GPS to ViewModel
    LaunchedEffect(Unit) {
        locationProvider.startLocationUpdates()
        locationProvider.currentLocation.collect { coords ->
            if (coords != null) {
                viewModel.setVerifierLocation(coords.lat, coords.lng)
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Progress timeline (hide on completion)
            if (uiState.phase != VerificationPhase.COMPLETE) {
                val steps = remember { listOf("Camera", "Decision", "Submit", "Complete") }
                val currentIndex = remember(uiState.phase) {
                    when (uiState.phase) {
                        VerificationPhase.CAMERA -> 0
                        VerificationPhase.DECISION -> 1
                        VerificationPhase.SUBMITTING -> 2
                        else -> 0 // COMPLETE excluded by outer guard
                    }
                }
                StatusTimeline(
                    steps = steps,
                    currentStepIndex = currentIndex,
                    modifier = Modifier
                        .padding(start = 16.dp, end = 16.dp, top = 48.dp, bottom = 8.dp),
                )
            }

            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                when (uiState.phase) {
                    VerificationPhase.CAMERA -> if (uiState.isLoadingSpot) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = GccGreen, modifier = Modifier.size(32.dp))
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    "Loading spot data...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextMuted,
                                )
                            }
                        }
                    } else CameraPhase(
                        spotLocation = uiState.spotCoordinates,
                        onScanCapture = { scanPhoto ->
                            viewModel.setScanPhoto(
                                scanPhoto,
                                "verify_${reportId}_${Clock.System.now().epochSeconds}.jpg",
                            )
                        },
                        onBack = onBack,
                    )
                    VerificationPhase.DECISION -> DecisionPhase(
                        error = uiState.error,
                        onStillDirty = { viewModel.submitVerification(SpotVerificationResult.STILL_DIRTY) },
                        onAlreadyClean = { viewModel.submitVerification(SpotVerificationResult.ALREADY_CLEAN) },
                        onBack = onBack,
                    )
                    VerificationPhase.SUBMITTING -> SubmittingPhase()
                    VerificationPhase.COMPLETE -> CompletePhase(
                        result = uiState.result,
                        pointsAwarded = uiState.pointsAwarded,
                        spotResolved = uiState.spotResolved,
                        onBack = onBack,
                    )
                }

                // Floating back button (hide on completion)
                if (uiState.phase != VerificationPhase.COMPLETE) {
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
                            Text(
                                "\u2190",
                                style = MaterialTheme.typography.titleMedium,
                                color = GccGreenDark,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Camera Phase ─────────────────────────────────────────────────

@Composable
private fun CameraPhase(
    spotLocation: Coordinates?,
    onScanCapture: (ScanCapturedPhoto) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SectionHeader(title = "Verify This Spot")
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Take a photo to show the current state of this spot.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (spotLocation != null) {
            SpotScanCapture(
                spotLocation = spotLocation,
                geofenceRadius = 30.0,
                maxPhotos = 1,
                onPhotoCaptured = onScanCapture,
                modifier = Modifier.weight(1f),
            )
        } else {
            CameraCapture(
                capturedPhotos = emptyList(),
                onCapture = { photo ->
                    onScanCapture(
                        ScanCapturedPhoto(
                            photoBytes = photo.bytes,
                            photoWidth = photo.width,
                            photoHeight = photo.height,
                            depthAnalysis = photo.depthAnalysis,
                            attestation = photo.attestation,
                        )
                    )
                },
                maxPhotos = 1,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ── Decision Phase ───────────────────────────────────────────────

@Composable
private fun DecisionPhase(
    error: String?,
    onStillDirty: () -> Unit,
    onAlreadyClean: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SectionHeader(title = "What Did You See?")
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Based on your photo, is the mess still there or has it been cleaned up?",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Photo preview placeholder
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(GccOnBackground.copy(alpha = 0.85f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "\u2705",
                    style = MaterialTheme.typography.displayMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Photo captured",
                    style = MaterialTheme.typography.titleMedium,
                    color = GccGreen,
                )
            }
        }

        error?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = GccError)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Still dirty → extends lifecycle, rewards 5pts
        GccButton(
            text = "Mess is still here",
            onClick = onStillDirty,
            variant = GccButtonVariant.PRIMARY,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Confirm the spot needs cleanup (+5 pts)",
            style = MaterialTheme.typography.bodySmall,
            color = TextSubtle,
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Already clean → resolves the spot, rewards 10pts
        GccButton(
            text = "It's been cleaned up",
            onClick = onAlreadyClean,
            variant = GccButtonVariant.OUTLINED,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Mark as resolved (+10 pts)",
            style = MaterialTheme.typography.bodySmall,
            color = TextSubtle,
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ── Submitting Phase ─────────────────────────────────────────────

@Composable
private fun SubmittingPhase() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = GccGreen)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Submitting verification...",
                style = MaterialTheme.typography.bodyLarge,
                color = TextMuted,
            )
        }
    }
}

// ── Complete Phase ───────────────────────────────────────────────

@Composable
private fun CompletePhase(
    result: SpotVerificationResult?,
    pointsAwarded: Int,
    spotResolved: Boolean,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            if (spotResolved) "\u2728" else "\u2705",
            style = MaterialTheme.typography.displayMedium,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            when {
                spotResolved -> "Spot Resolved!"
                result == SpotVerificationResult.STILL_DIRTY -> "Verification Recorded"
                else -> "Thank You!"
            },
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = GccGreen,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            when {
                spotResolved -> "This spot has been confirmed clean and removed from the active list."
                result == SpotVerificationResult.STILL_DIRTY -> "The mess is confirmed still there. It will remain on the map for volunteers."
                else -> "Your verification has been recorded."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
            textAlign = TextAlign.Center,
        )

        if (pointsAwarded > 0) {
            Spacer(modifier = Modifier.height(24.dp))
            GccCard(accentColor = CardAccentSky) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            "Verification Bonus",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        )
                        Text(
                            "Thanks for checking this spot!",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                        )
                    }
                    Text(
                        "+$pointsAwarded",
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                        color = GccSky,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        GccButton(
            text = "Back to Map",
            onClick = onBack,
            variant = GccButtonVariant.PRIMARY,
        )
    }
}
