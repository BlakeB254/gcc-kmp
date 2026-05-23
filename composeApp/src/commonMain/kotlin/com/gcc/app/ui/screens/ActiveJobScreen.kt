package com.gcc.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcc.app.platform.CameraManager
import com.gcc.app.platform.CapturedPhoto
import com.gcc.app.platform.LocationProvider
import com.gcc.app.ui.camera.PhotoComparisonSheet
import com.gcc.app.ui.components.*
import com.gcc.app.ui.map.ActiveJobOverlay
import com.gcc.app.ui.map.CheckpointMarker
import com.gcc.app.ui.map.GccMap
import com.gcc.app.ui.map.MapStyles
import com.gcc.app.ui.theme.*
import com.gcc.app.viewmodel.ActiveJobViewModel
import com.gcc.app.viewmodel.CheckpointStatus
import com.gcc.app.util.fmt
import com.gcc.shared.model.Job
import com.gcc.shared.model.JobStatus
import com.gcc.shared.model.TEST_ASSIGNMENT_START_WINDOW_MINUTES
import com.gcc.shared.model.isTestAssignment
import com.gcc.shared.model.testAssignmentStartDeadlineMillis
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import org.koin.compose.koinInject

@Composable
fun ActiveJobScreen(
    jobId: Int = 0,
    routeId: Int = 0,
    onBack: () -> Unit = {},
    onStartScan: (Int) -> Unit = {},
) {
    val viewModel = koinInject<ActiveJobViewModel>()
    val locationProvider = koinInject<LocationProvider>()
    val uiState by viewModel.uiState.collectAsState()
    val currentLocation by locationProvider.currentLocation.collectAsState()
    val cameraManager = remember { CameraManager() }
    val captureScope = rememberCoroutineScope()
    var beforePhoto by remember(uiState.currentCheckpointIndex) { mutableStateOf<CapturedPhoto?>(null) }
    var afterPhoto by remember(uiState.currentCheckpointIndex) { mutableStateOf<CapturedPhoto?>(null) }
    var photoCaptureError by remember(uiState.currentCheckpointIndex) { mutableStateOf<String?>(null) }
    var nowMillis by remember { mutableStateOf(Clock.System.now().toEpochMilliseconds()) }
    var shouldOpenRouteExperience by remember { mutableStateOf(false) }

    fun captureEvidencePhoto(onCaptured: (CapturedPhoto) -> Unit) {
        captureScope.launch {
            photoCaptureError = null
            val photo = try {
                cameraManager.capturePhoto()
            } catch (e: Exception) {
                photoCaptureError = e.message ?: "Photo capture failed"
                null
            }
            if (photo != null) {
                onCaptured(photo)
            } else if (photoCaptureError == null) {
                photoCaptureError = "Camera unavailable. Try again or use a device with camera access."
            }
        }
    }

    LaunchedEffect(jobId, routeId) {
        viewModel.loadJob(jobId, routeId)
    }

    LaunchedEffect(currentLocation) {
        currentLocation?.let { viewModel.updateLocation(it) }
    }

    DisposableEffect(Unit) {
        locationProvider.startLocationUpdates()
        onDispose {
            locationProvider.stopLocationUpdates()
        }
    }

    LaunchedEffect(uiState.job?.id, uiState.jobStarted) {
        while (uiState.job?.isTestAssignment() == true && uiState.job?.status == JobStatus.CLAIMED) {
            nowMillis = Clock.System.now().toEpochMilliseconds()
            delay(1000L)
        }
    }

    LaunchedEffect(shouldOpenRouteExperience, uiState.jobStarted, uiState.route?.id) {
        val routeIdToOpen = uiState.route?.id
        if (shouldOpenRouteExperience && uiState.jobStarted && routeIdToOpen != null) {
            shouldOpenRouteExperience = false
            onStartScan(routeIdToOpen)
        }
    }

    if (uiState.isLoading) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = GccGreen)
        }
        return
    }

    // All checkpoints completed — show job complete state
    val allCheckpointsDone = uiState.checkpoints.isNotEmpty() &&
        uiState.currentCheckpointIndex >= uiState.checkpoints.size

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Map section (55% height)
            Box(modifier = Modifier.weight(0.55f)) {
                GccMap(
                    modifier = Modifier.fillMaxSize(),
                    centerLat = uiState.currentLocation?.lat
                        ?: uiState.route?.routeStartCoordinates()?.lat
                        ?: MapStyles.NORTH_LAWNDALE_CENTER_LAT,
                    centerLng = uiState.currentLocation?.lng
                        ?: uiState.route?.routeStartCoordinates()?.lng
                        ?: MapStyles.NORTH_LAWNDALE_CENTER_LNG,
                    zoom = MapStyles.ZOOM_ACTIVE_JOB,
                    currentLocation = uiState.currentLocation,
                    routeCoordinates = uiState.route?.let { listOf(it.routePathCoordinates()) } ?: emptyList(),
                ) {
                    ActiveJobOverlay(
                        checkpoints = uiState.checkpoints,
                        currentLocation = uiState.currentLocation,
                        currentCheckpointIndex = uiState.currentCheckpointIndex,
                        isWithinGeofence = uiState.isWithinGeofence,
                        modifier = Modifier.align(Alignment.TopStart),
                    )
                }
            }

            // Bottom panel
            Card(
                modifier = Modifier.weight(0.45f),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                ) {
                    // Drag handle
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .width(40.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(TextSubtle.copy(alpha = 0.4f))
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Show "Start Route" button if job is claimed but not yet started
                    if (!uiState.jobStarted && !uiState.jobCompleted) {
                        val countdown = testAssignmentCountdownText(uiState.job, nowMillis)
                        val startWindowExpired = countdown?.isExpired == true
                        val canStartRoute = uiState.isWithinStartArea && !startWindowExpired
                        if (startWindowExpired) {
                            SectionHeader(title = "Assignment Expired")
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "This test assignment is no longer active. It will disappear from the dashboard after refresh; ask an admin to reassign it for a new start window.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextMuted,
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            GccButton(
                                text = "Back to Dashboard",
                                onClick = onBack,
                                variant = GccButtonVariant.PRIMARY,
                            )
                        } else {
                            SectionHeader(title = "Ready to Begin")
                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                "Go to the assigned route boundary to accept and start.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextMuted,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = uiState.distanceToRouteMeters?.let { distance ->
                                    if (uiState.isWithinStartArea) {
                                        "GPS confirmed you are at the route boundary."
                                    } else {
                                        "Route boundary: ${distance.fmt(0)}m away"
                                    }
                                } ?: "Waiting for GPS to confirm your distance to the route boundary.",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                color = if (uiState.isWithinStartArea) GccGreen else TextMuted,
                            )
                            Spacer(modifier = Modifier.weight(1f))

                            uiState.error?.let { error ->
                                Text(error, style = MaterialTheme.typography.bodySmall, color = GccError)
                                Spacer(modifier = Modifier.height(8.dp))
                            }

                            countdown?.let {
                                TestAssignmentCountdownCard(
                                    text = it.text,
                                    isExpired = false,
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                            }

                            GccButton(
                                text = if (uiState.isWithinStartArea) "Start Route" else "Go to Location to Start",
                                onClick = {
                                    shouldOpenRouteExperience = true
                                    viewModel.startJob()
                                },
                                variant = GccButtonVariant.PRIMARY,
                                enabled = !uiState.isStarting && canStartRoute,
                                isLoading = uiState.isStarting,
                            )
                            if (uiState.job?.status == JobStatus.CLAIMED && !uiState.isWithinStartArea) {
                                Spacer(modifier = Modifier.height(8.dp))
                                GccButton(
                                    text = if (uiState.isDeclining) "Declining..." else "Decline Assignment",
                                    onClick = { viewModel.declineJob(onBack) },
                                    variant = GccButtonVariant.OUTLINED,
                                    enabled = !uiState.isDeclining,
                                    isLoading = uiState.isDeclining,
                                )
                            }
                        }
                    } else if (uiState.jobCompleted || allCheckpointsDone) {
                        // Job completed — show success
                        SectionHeader(title = "Route Complete!")
                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            "All checkpoints verified. Great work!",
                            style = MaterialTheme.typography.bodyMedium,
                            color = GccGreen,
                        )
                        Spacer(modifier = Modifier.weight(1f))

                        GccButton(
                            text = "Back to Dashboard",
                            onClick = onBack,
                            variant = GccButtonVariant.PRIMARY,
                        )
                    } else {
                        // Job in progress — show checkpoint checklist
                        SectionHeader(title = "Checkpoint Checklist")
                        Spacer(modifier = Modifier.height(8.dp))

                        // Admin skip-to-complete shortcut
                        if (uiState.isAdminMode) {
                            GccButton(
                                text = "Admin: Skip to Complete",
                                onClick = { viewModel.adminCompleteAll() },
                                variant = GccButtonVariant.SECONDARY,
                                isLoading = uiState.isCompleting,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            itemsIndexed(
                                uiState.checkpoints,
                                key = { _, item -> item.checkpoint.sequenceOrder },
                            ) { index, item ->
                                ChecklistItem(
                                    index = index + 1,
                                    item = item,
                                )
                            }
                        }

                        // Action button for current checkpoint
                        val currentCheckpoint = uiState.checkpoints.getOrNull(uiState.currentCheckpointIndex)
                        val canComplete = uiState.isWithinGeofence || uiState.isAdminMode
                        if (currentCheckpoint != null) {
                            Spacer(modifier = Modifier.height(8.dp))

                            if (currentCheckpoint.checkpoint.photoRequired && canComplete && !uiState.isAdminMode) {
                                PhotoComparisonSheet(
                                    checkpointName = currentCheckpoint.checkpoint.name,
                                    hasBeforePhoto = beforePhoto != null,
                                    hasAfterPhoto = afterPhoto != null,
                                    onTakeBeforePhoto = {
                                        captureEvidencePhoto { beforePhoto = it }
                                    },
                                    onTakeAfterPhoto = {
                                        captureEvidencePhoto { afterPhoto = it }
                                    },
                                    onConfirmAndUpload = {
                                        val before = beforePhoto ?: return@PhotoComparisonSheet
                                        val after = afterPhoto ?: return@PhotoComparisonSheet
                                        viewModel.submitCheckpointEvidence(
                                            checkpointId = currentCheckpoint.checkpoint.id,
                                            beforePhotoBytes = before.bytes,
                                            afterPhotoBytes = after.bytes,
                                        )
                                    },
                                    isUploading = uiState.isUploadingEvidence,
                                )
                            } else if (canComplete) {
                                GccButton(
                                    text = "Complete Checkpoint",
                                    onClick = { viewModel.completeCurrentCheckpoint() },
                                    variant = GccButtonVariant.PRIMARY,
                                )
                            } else {
                                GccButton(
                                    text = "Move closer to checkpoint (${currentCheckpoint.distanceMeters?.let { "${it.fmt(0)}m" } ?: "..."})",
                                    onClick = {},
                                    variant = GccButtonVariant.OUTLINED,
                                    enabled = false,
                                )
                            }
                        }

                        uiState.error?.let { error ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(error, style = MaterialTheme.typography.bodySmall, color = GccError)
                        }
                        photoCaptureError?.let { error ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(error, style = MaterialTheme.typography.bodySmall, color = GccError)
                        }
                    }
                }
            }
        }

        // Floating status card on top of map
        uiState.route?.let { route ->
            GccCard(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 8.dp, start = 56.dp, end = 16.dp),
                elevation = 4.dp,
            ) {
                Text(
                    route.displayName,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                )
                Text(
                    "${uiState.currentCheckpointIndex}/${uiState.checkpoints.size} checkpoints",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                )
            }
        }

        // Floating back button
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

private fun com.gcc.shared.model.Route.routePathCoordinates(): List<com.gcc.shared.model.Coordinates> =
    geometry?.coordinates
        ?.mapNotNull { point ->
            val lng = point.getOrNull(0)
            val lat = point.getOrNull(1)
            if (lat != null && lng != null) com.gcc.shared.model.Coordinates(lng = lng, lat = lat) else null
        }
        ?.takeIf { it.size >= 2 }
        ?: checkpoints.sortedBy { it.sequenceOrder }.map { it.coordinates }

private fun com.gcc.shared.model.Route.routeStartCoordinates(): com.gcc.shared.model.Coordinates? =
    routePathCoordinates().firstOrNull()

private data class TestAssignmentCountdown(
    val text: String,
    val isExpired: Boolean,
)

private fun testAssignmentCountdownText(job: Job?, nowMillis: Long): TestAssignmentCountdown? {
    if (job?.isTestAssignment() != true || job.status != JobStatus.CLAIMED) return null
    val deadline = job.testAssignmentStartDeadlineMillis() ?: return null
    val remainingMillis = deadline - nowMillis
    if (remainingMillis <= 0) {
        return TestAssignmentCountdown(
            text = "Test start window expired. Ask an admin to reassign the route.",
            isExpired = true,
        )
    }
    val totalSeconds = remainingMillis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return TestAssignmentCountdown(
        text = "Start this test route within ${minutes}:${seconds.toString().padStart(2, '0')} (${TEST_ASSIGNMENT_START_WINDOW_MINUTES} min window).",
        isExpired = false,
    )
}

@Composable
private fun TestAssignmentCountdownCard(
    text: String,
    isExpired: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = (if (isExpired) GccError else GccClaimed).copy(alpha = 0.12f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = if (isExpired) GccError else GccClaimed,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun ChecklistItem(
    index: Int,
    item: com.gcc.app.viewmodel.CheckpointUiItem,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CheckpointMarker(
            sequenceNumber = index,
            type = item.checkpoint.type,
            status = item.status,
            photoRequired = item.checkpoint.photoRequired,
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.checkpoint.name,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (item.status == CheckpointStatus.CURRENT) FontWeight.Bold
                    else FontWeight.Normal,
                ),
                color = when (item.status) {
                    CheckpointStatus.COMPLETED -> MapCheckpointCompleted
                    CheckpointStatus.CURRENT -> MapCheckpointCurrent
                    CheckpointStatus.UPCOMING -> TextSubtle
                },
            )
            if (item.checkpoint.photoRequired) {
                Text(
                    "\uD83D\uDCF7 Photo required",
                    style = MaterialTheme.typography.bodySmall,
                    color = GccGreen.copy(
                        alpha = if (item.status == CheckpointStatus.UPCOMING) 0.4f else 1f,
                    ),
                )
            }
        }

        item.distanceMeters?.let { dist ->
            Text(
                text = if (dist >= 1000) "${(dist / 1000).fmt(1)} km"
                else "${dist.toInt()} m",
                style = MaterialTheme.typography.bodySmall,
                color = TextSubtle,
            )
        }
    }
}
