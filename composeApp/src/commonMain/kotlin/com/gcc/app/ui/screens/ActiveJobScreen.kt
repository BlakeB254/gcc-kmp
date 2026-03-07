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
import org.koin.compose.koinInject

@Composable
fun ActiveJobScreen(
    jobId: Int = 0,
    routeId: Int = 0,
    onBack: () -> Unit = {},
) {
    val viewModel = koinInject<ActiveJobViewModel>()
    val uiState by viewModel.uiState.collectAsState()
    var hasBeforePhoto by remember { mutableStateOf(false) }
    var hasAfterPhoto by remember { mutableStateOf(false) }

    LaunchedEffect(jobId, routeId) {
        viewModel.loadJob(jobId, routeId)
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
                    centerLat = uiState.currentLocation?.lat ?: MapStyles.CHICAGO_CENTER_LAT,
                    centerLng = uiState.currentLocation?.lng ?: MapStyles.CHICAGO_CENTER_LNG,
                    zoom = MapStyles.ZOOM_ACTIVE_JOB,
                    currentLocation = uiState.currentLocation,
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
                        SectionHeader(title = "Ready to Begin")
                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            "Tap 'Start Route' when you arrive at the first checkpoint.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMuted,
                        )
                        Spacer(modifier = Modifier.weight(1f))

                        uiState.error?.let { error ->
                            Text(error, style = MaterialTheme.typography.bodySmall, color = GccError)
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        GccButton(
                            text = "Start Route",
                            onClick = { viewModel.startJob() },
                            variant = GccButtonVariant.PRIMARY,
                            enabled = !uiState.isStarting,
                            isLoading = uiState.isStarting,
                        )
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
                        if (currentCheckpoint != null) {
                            Spacer(modifier = Modifier.height(8.dp))

                            if (currentCheckpoint.checkpoint.photoRequired && uiState.isWithinGeofence) {
                                PhotoComparisonSheet(
                                    checkpointName = currentCheckpoint.checkpoint.name,
                                    hasBeforePhoto = hasBeforePhoto,
                                    hasAfterPhoto = hasAfterPhoto,
                                    onTakeBeforePhoto = { hasBeforePhoto = true },
                                    onTakeAfterPhoto = { hasAfterPhoto = true },
                                    onConfirmAndUpload = {
                                        viewModel.completeCurrentCheckpoint()
                                        hasBeforePhoto = false
                                        hasAfterPhoto = false
                                    },
                                )
                            } else if (uiState.isWithinGeofence) {
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

                        // "Complete Job" button when all checkpoints are done
                        // (fallback — allCheckpointsDone above should catch most cases)
                        uiState.error?.let { error ->
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
