package com.gcc.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.platform.LocalUriHandler
import com.gcc.app.ui.components.*
import com.gcc.app.ui.map.GccMap
import com.gcc.app.ui.map.MapStyles
import com.gcc.app.ui.theme.*
import com.gcc.app.viewmodel.VolunteerCleanupViewModel
import com.gcc.app.viewmodel.VolunteerPhase
import com.gcc.app.util.padTwo
import com.gcc.shared.model.Coordinates
import com.gcc.shared.model.ScanCapturedPhoto
import org.koin.compose.koinInject

@Composable
fun VolunteerCleanupScreen(
    reportId: Int = 0,
    onBack: () -> Unit = {},
) {
    val viewModel = koinInject<VolunteerCleanupViewModel>()
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(reportId) {
        viewModel.initForReport(reportId)
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.onCleared() }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Progress timeline (hide on completion phases)
            if (uiState.phase != VolunteerPhase.COMPLETE &&
                uiState.phase != VolunteerPhase.ALREADY_CLEAN_COMPLETE
            ) {
                val steps = remember { listOf("Verify Spot", "Cleanup", "After Photos", "Trash Heap", "Submit") }
                val currentIndex = remember(uiState.phase) {
                    when (uiState.phase) {
                        VolunteerPhase.BEFORE_PHOTOS -> 0
                        VolunteerPhase.IN_PROGRESS -> 1
                        VolunteerPhase.AFTER_PHOTOS -> 2
                        VolunteerPhase.TRASH_HEAP_PHOTO -> 3
                        VolunteerPhase.SUBMITTING -> 4
                        else -> 0
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
                    VolunteerPhase.BEFORE_PHOTOS -> BeforePhotosPhase(
                        beforePhotoCount = uiState.beforePhotoUrls.size,
                        isSubmitting = uiState.isSubmitting,
                        error = uiState.error,
                        onCapture = { viewModel.addBeforePhoto("before_${uiState.beforePhotoUrls.size + 1}.jpg") },
                        spotLocation = uiState.spotLocation,
                        onScanCapture = { viewModel.addBeforeScanPhoto(it) },
                        onConfirmMessStillHere = { viewModel.startCleanup() },
                        onAlreadyClean = { viewModel.reportAlreadyClean() },
                        onBack = onBack,
                    )

                    VolunteerPhase.IN_PROGRESS -> InProgressPhase(
                        elapsedSeconds = uiState.elapsedSeconds,
                        cleanup = uiState.cleanup,
                        error = uiState.error,
                        onTakeAfterPhotos = { viewModel.advancePhase() },
                        onBack = onBack,
                    )

                    VolunteerPhase.AFTER_PHOTOS -> AfterPhotosPhase(
                        afterPhotoCount = uiState.afterPhotoUrls.size,
                        onCapture = { viewModel.addAfterPhoto("after_${uiState.afterPhotoUrls.size + 1}.jpg") },
                        spotLocation = uiState.spotLocation,
                        onScanCapture = { viewModel.addAfterScanPhoto(it) },
                        onNext = { viewModel.advancePhase() },
                    )

                    VolunteerPhase.TRASH_HEAP_PHOTO -> TrashHeapPhase(
                        hasTrashHeap = uiState.trashHeapUrl != null,
                        onCapture = { viewModel.setTrashHeapUrl("trash_heap.jpg") },
                        spotLocation = uiState.spotLocation,
                        onScanCapture = { viewModel.setTrashHeapScanPhoto(it) },
                        onSubmit = { viewModel.advancePhase() },
                    )

                    VolunteerPhase.SUBMITTING -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = GccGreen)
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    "Verifying cleanup...",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = TextMuted,
                                )
                            }
                        }
                    }

                    VolunteerPhase.COMPLETE -> CompletionPhase(
                        cleanup = uiState.cleanup,
                        elapsedSeconds = uiState.elapsedSeconds,
                        pointsAwarded = uiState.pointsAwarded,
                        onBack = onBack,
                    )

                    VolunteerPhase.ALREADY_CLEAN_COMPLETE -> AlreadyCleanCompletionPhase(
                        pointsAwarded = uiState.pointsAwarded,
                        onBack = onBack,
                    )
                }

                // Floating back button
                if (uiState.phase != VolunteerPhase.COMPLETE &&
                    uiState.phase != VolunteerPhase.ALREADY_CLEAN_COMPLETE
                ) {
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

// ── Before Photos Phase ──────────────────────────────────────────────

@Composable
private fun BeforePhotosPhase(
    beforePhotoCount: Int,
    isSubmitting: Boolean,
    error: String?,
    onCapture: () -> Unit,
    spotLocation: Coordinates?,
    onScanCapture: (ScanCapturedPhoto) -> Unit,
    onConfirmMessStillHere: () -> Unit,
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
        SectionHeader(title = "Verify Spot")
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Take a photo to verify the mess is still here before starting cleanup.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (spotLocation != null) {
            SpotScanCapture(
                spotLocation = spotLocation,
                geofenceRadius = 30.0,
                maxPhotos = 3,
                onPhotoCaptured = onScanCapture,
                modifier = Modifier.fillMaxWidth().height(300.dp),
            )
        } else {
            // Fallback placeholder when spot location is not yet loaded
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
                        "\uD83D\uDCF7",
                        style = MaterialTheme.typography.displayLarge,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Before Photo",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                    if (beforePhotoCount > 0) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "$beforePhotoCount photo${if (beforePhotoCount != 1) "s" else ""} captured",
                            style = MaterialTheme.typography.bodyMedium,
                            color = GccGreen,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = onCapture,
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = GccGreen),
            ) {
                Text(
                    "\uD83D\uDCF8",
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
        }

        error?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = GccError)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Action buttons
        if (beforePhotoCount > 0) {
            GccButton(
                text = if (isSubmitting) "Starting..." else "Mess is still here - Start Cleanup",
                onClick = onConfirmMessStillHere,
                variant = GccButtonVariant.PRIMARY,
                enabled = !isSubmitting,
            )

            Spacer(modifier = Modifier.height(8.dp))

            GccButton(
                text = if (isSubmitting) "Reporting..." else "Already Clean",
                onClick = onAlreadyClean,
                variant = GccButtonVariant.OUTLINED,
                enabled = !isSubmitting,
            )

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Report a bonus if someone already cleaned it",
                style = MaterialTheme.typography.bodySmall,
                color = TextSubtle,
            )
        }
    }
}

// ── Already Clean Completion ─────────────────────────────────────────

@Composable
private fun AlreadyCleanCompletionPhase(
    pointsAwarded: Int,
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
            "\u2728",
            style = MaterialTheme.typography.displayMedium,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            "Spot Already Clean!",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = GccGreen,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Thank you for verifying. The spot has been marked as resolved.",
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
                            "Report Bonus",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        )
                        Text(
                            "Thanks for checking on this spot!",
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

// ── Timer ────────────────────────────────────────────────────────────

@Composable
private fun TimerDisplay(
    elapsedSeconds: Long,
    modifier: Modifier = Modifier,
) {
    val hours = (elapsedSeconds / 3600).toInt()
    val minutes = ((elapsedSeconds % 3600) / 60).toInt()
    val seconds = (elapsedSeconds % 60).toInt()

    val timeText = if (hours > 0) {
        "${hours}:${minutes.padTwo()}:${seconds.padTwo()}"
    } else {
        "${minutes.padTwo()}:${seconds.padTwo()}"
    }

    Text(
        text = timeText,
        style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
        color = GccGreen,
        modifier = modifier,
    )
}

// ── In Progress Phase ────────────────────────────────────────────────

@Composable
private fun InProgressPhase(
    elapsedSeconds: Long,
    cleanup: com.gcc.shared.model.VolunteerCleanup?,
    error: String?,
    onTakeAfterPhotos: () -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Map section (40% height)
        Box(modifier = Modifier.weight(0.4f).fillMaxWidth()) {
            val lat = cleanup?.locationLat ?: MapStyles.NORTH_LAWNDALE_CENTER_LAT
            val lng = cleanup?.locationLng ?: MapStyles.NORTH_LAWNDALE_CENTER_LNG
            GccMap(
                modifier = Modifier.fillMaxSize(),
                centerLat = lat,
                centerLng = lng,
                zoom = MapStyles.ZOOM_ACTIVE_JOB,
                currentLocation = Coordinates(lng = lng, lat = lat),
            )
        }

        // Bottom panel
        Card(
            modifier = Modifier.weight(0.6f),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Drag handle
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(TextSubtle.copy(alpha = 0.4f))
                )
                Spacer(modifier = Modifier.height(16.dp))

                SectionHeader(title = "Cleanup In Progress")
                Spacer(modifier = Modifier.height(16.dp))

                // Timer
                TimerDisplay(elapsedSeconds = elapsedSeconds)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Time elapsed",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Status card
                GccCard(accentColor = CardAccentGreen) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "Status",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMuted,
                        )
                        StatusChip(
                            text = "In Progress",
                            dotColor = GccInProgress,
                            backgroundColor = ChipBgInProgress,
                        )
                    }
                }

                error?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = GccError)
                }

                Spacer(modifier = Modifier.weight(1f))

                Text(
                    "When done cleaning, take after photos to verify.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    modifier = Modifier.padding(bottom = 8.dp),
                )

                GccButton(
                    text = "Take After Photos",
                    onClick = onTakeAfterPhotos,
                    variant = GccButtonVariant.PRIMARY,
                )
            }
        }
    }
}

// ── After Photos Phase ───────────────────────────────────────────────

@Composable
private fun AfterPhotosPhase(
    afterPhotoCount: Int,
    onCapture: () -> Unit,
    spotLocation: Coordinates?,
    onScanCapture: (ScanCapturedPhoto) -> Unit,
    onNext: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SectionHeader(title = "After Photos")
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Take photos of the cleaned area.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (spotLocation != null) {
            SpotScanCapture(
                spotLocation = spotLocation,
                geofenceRadius = 30.0,
                maxPhotos = 3,
                onPhotoCaptured = onScanCapture,
                modifier = Modifier.fillMaxWidth().height(300.dp),
            )
        } else {
            // Fallback placeholder when spot location is not yet loaded
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
                        "\uD83D\uDCF7",
                        style = MaterialTheme.typography.displayLarge,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "After Photo",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                    if (afterPhotoCount > 0) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "$afterPhotoCount photo${if (afterPhotoCount != 1) "s" else ""} captured",
                            style = MaterialTheme.typography.bodyMedium,
                            color = GccGreen,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = onCapture,
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = GccGreen),
            ) {
                Text(
                    "\uD83D\uDCF8",
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        GccButton(
            text = "Next: Trash Heap Photo",
            onClick = onNext,
            variant = GccButtonVariant.PRIMARY,
            enabled = afterPhotoCount > 0,
        )
    }
}

// ── Trash Heap Phase ─────────────────────────────────────────────────

@Composable
private fun TrashHeapPhase(
    hasTrashHeap: Boolean,
    onCapture: () -> Unit,
    spotLocation: Coordinates?,
    onScanCapture: (ScanCapturedPhoto) -> Unit,
    onSubmit: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SectionHeader(title = "Trash Heap Photo")
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Optionally take a photo of the collected trash heap for AI analysis.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (!hasTrashHeap) {
            if (spotLocation != null) {
                SpotScanCapture(
                    spotLocation = spotLocation,
                    geofenceRadius = 30.0,
                    maxPhotos = 1,
                    requireGeofence = false,
                    onPhotoCaptured = onScanCapture,
                    modifier = Modifier.fillMaxWidth().height(300.dp),
                )
            } else {
                // Fallback placeholder when spot location is not yet loaded
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
                            "\uD83D\uDDD1\uFE0F",
                            style = MaterialTheme.typography.displayLarge,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "Trash Heap",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedButton(
                    onClick = onCapture,
                    modifier = Modifier.size(72.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = GccGreen),
                ) {
                    Text(
                        "\uD83D\uDCF8",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        } else {
            // Show captured state
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
                        "\uD83D\uDDD1\uFE0F",
                        style = MaterialTheme.typography.displayLarge,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Photo captured",
                        style = MaterialTheme.typography.titleMedium,
                        color = GccGreen,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        GccButton(
            text = "Submit Cleanup",
            onClick = onSubmit,
            variant = GccButtonVariant.PRIMARY,
        )

        if (!hasTrashHeap) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "You can skip this step.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSubtle,
            )
        }
    }
}

// ── Completion Phase ─────────────────────────────────────────────────

@Composable
private fun CompletionPhase(
    cleanup: com.gcc.shared.model.VolunteerCleanup?,
    elapsedSeconds: Long,
    pointsAwarded: Int,
    onBack: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Spacer(modifier = Modifier.height(24.dp))
        }

        // Success header
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "\u2705",
                    style = MaterialTheme.typography.displayMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Cleanup Complete!",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = GccGreen,
                )
            }
        }

        // Verification card
        item {
            GccCard(accentColor = CardAccentGreen) {
                Text(
                    "Verification Summary",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Duration
                val minutes = (elapsedSeconds / 60).toInt()
                val seconds = (elapsedSeconds % 60).toInt()
                SummaryRow(label = "Duration", value = "${minutes}m ${seconds}s")

                cleanup?.let {
                    SummaryRow(
                        label = "Before Photos",
                        value = "${it.beforeMediaUrls.size}",
                    )
                    SummaryRow(
                        label = "After Photos",
                        value = "${it.afterMediaUrls.size}",
                    )
                    SummaryRow(
                        label = "Trash Heap Photo",
                        value = if (it.trashHeapMediaUrl != null) "Yes" else "Skipped",
                    )
                    if (it.locationVerified) {
                        SummaryRow(
                            label = "Location",
                            value = "Verified",
                            valueColor = GccGreen,
                        )
                    }
                }
            }
        }

        // Points card
        item {
            if (pointsAwarded > 0) {
                GccCard(accentColor = CardAccentSky) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                "Points Earned",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            )
                            Text(
                                "Thank you for volunteering!",
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
        }

        // AI analysis
        item {
            cleanup?.aiTrashAnalysis?.let { analysis ->
                GccCard(accentColor = CardAccentPurple) {
                    Text(
                        "AI Trash Analysis",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = CardAccentPurple,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    analysis.estimatedWeightLbs?.let {
                        Text(
                            "Estimated weight: ${it.toInt()} lbs",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                        )
                    }
                    if (analysis.trashTypes.isNotEmpty()) {
                        Text(
                            "Types: ${analysis.trashTypes.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                        )
                    }
                }
            }
        }

        // Download PDF report
        item {
            val pdfUrl = cleanup?.pdfUrl
            val uriHandler = LocalUriHandler.current
            GccButton(
                text = "Download PDF Report",
                onClick = { pdfUrl?.let { uriHandler.openUri(it) } },
                variant = GccButtonVariant.OUTLINED,
                enabled = pdfUrl != null,
            )
        }

        // Back button
        item {
            GccButton(
                text = "Back to Dashboard",
                onClick = onBack,
                variant = GccButtonVariant.PRIMARY,
            )
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

// ── Helpers ──────────────────────────────────────────────────────────

@Composable
private fun SummaryRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = valueColor,
        )
    }
}
