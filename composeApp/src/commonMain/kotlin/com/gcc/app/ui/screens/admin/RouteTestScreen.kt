package com.gcc.app.ui.screens.admin

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
import com.gcc.app.platform.LocationProvider
import com.gcc.app.ui.components.*
import com.gcc.app.ui.map.ActiveJobOverlay
import com.gcc.app.ui.map.CheckpointMarker
import com.gcc.app.ui.map.GccMap
import com.gcc.app.ui.map.MapStyles
import com.gcc.app.ui.theme.*
import com.gcc.app.viewmodel.CheckpointStatus
import com.gcc.app.viewmodel.CheckpointUiItem
import com.gcc.app.viewmodel.RouteTestViewModel
import com.gcc.app.util.fmt
import org.koin.compose.koinInject

private val AmberBadge = Color(0xFFF59E0B)
private val AmberBadgeDark = Color(0xFFD97706)

@Composable
fun RouteTestScreen(
    routeId: Int,
    onBack: () -> Unit,
) {
    val viewModel = koinInject<RouteTestViewModel>()
    val uiState by viewModel.uiState.collectAsState()
    val locationProvider = koinInject<LocationProvider>()
    val currentLocation by locationProvider.currentLocation.collectAsState()

    LaunchedEffect(routeId) {
        viewModel.loadRoute(routeId)
    }

    // Feed location updates to the ViewModel
    LaunchedEffect(currentLocation) {
        currentLocation?.let { viewModel.updateLocation(it) }
    }

    DisposableEffect(Unit) {
        locationProvider.startLocationUpdates()
        onDispose {
            locationProvider.stopLocationUpdates()
            viewModel.cleanup()
            viewModel.onCleared()
        }
    }

    if (uiState.isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = GccGreen)
        }
        return
    }

    uiState.error?.let { error ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(error, color = GccError)
                Spacer(Modifier.height(16.dp))
                GccButton(text = "Back", onClick = onBack, variant = GccButtonVariant.OUTLINED)
            }
        }
        return
    }

    // Completion screen
    if (uiState.isComplete) {
        CompletionSummary(
            routeName = uiState.route?.displayName ?: "Route",
            totalSeconds = uiState.elapsedSeconds,
            lap1Seconds = uiState.lap1Seconds,
            checkpointCount = uiState.checkpoints.size,
            onRunAgain = { viewModel.resetTest() },
            onBack = onBack,
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Map section (55% height)
            Box(modifier = Modifier.weight(0.55f)) {
                val routeCoords = remember(uiState.route) {
                    uiState.route?.geometry?.coordinates?.map {
                        com.gcc.shared.model.Coordinates(lng = it[0], lat = it[1])
                    } ?: uiState.route?.checkpoints
                        ?.sortedBy { it.sequenceOrder }
                        ?.map { it.coordinates }
                    ?: emptyList()
                }

                GccMap(
                    modifier = Modifier.fillMaxSize(),
                    centerLat = uiState.currentLocation?.lat ?: MapStyles.CHICAGO_CENTER_LAT,
                    centerLng = uiState.currentLocation?.lng ?: MapStyles.CHICAGO_CENTER_LNG,
                    zoom = MapStyles.ZOOM_ACTIVE_JOB,
                    currentLocation = uiState.currentLocation,
                    routeCoordinates = if (routeCoords.isNotEmpty()) listOf(routeCoords) else emptyList(),
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

            // Bottom panel (45% height)
            Card(
                modifier = Modifier.weight(0.45f),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    // Drag handle
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .width(40.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(TextSubtle.copy(alpha = 0.4f))
                    )
                    Spacer(Modifier.height(12.dp))

                    if (!uiState.isRunning && !uiState.isLapTransition) {
                        // Not started yet
                        SectionHeader(title = "Route Test Mode")
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Walk the route checkpoints in 2 laps. No job created, no payout triggered.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMuted,
                        )
                        Spacer(Modifier.weight(1f))
                        GccButton(
                            text = "Start Test",
                            onClick = { viewModel.startTest() },
                            variant = GccButtonVariant.PRIMARY,
                        )
                    } else if (uiState.isLapTransition) {
                        // Lap 1 → Lap 2 transition
                        LapTransitionPanel(
                            lap1Seconds = uiState.lap1Seconds,
                            checkpointCount = uiState.checkpoints.size,
                            onStartLap2 = { viewModel.startLap2() },
                        )
                    } else {
                        // In-progress: show checkpoint checklist
                        val lapLabel = if (uiState.lap == 1) "Before" else "Verify"
                        SectionHeader(title = "Lap ${uiState.lap} — $lapLabel")
                        Spacer(Modifier.height(8.dp))

                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            itemsIndexed(
                                uiState.checkpoints,
                                key = { _, item -> item.checkpoint.sequenceOrder },
                            ) { index, item ->
                                TestChecklistItem(index = index + 1, item = item)
                            }
                        }

                        // Action buttons
                        val currentCheckpoint = uiState.checkpoints.getOrNull(uiState.currentCheckpointIndex)
                        if (currentCheckpoint != null) {
                            Spacer(Modifier.height(8.dp))
                            val canComplete = uiState.isWithinGeofence

                            if (canComplete) {
                                GccButton(
                                    text = "Complete Checkpoint",
                                    onClick = { viewModel.completeCurrentCheckpoint() },
                                    variant = GccButtonVariant.PRIMARY,
                                )
                            } else {
                                GccButton(
                                    text = "Move closer (${currentCheckpoint.distanceMeters?.let { "${it.fmt(0)}m" } ?: "..."})",
                                    onClick = {},
                                    variant = GccButtonVariant.OUTLINED,
                                    enabled = false,
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            GccButton(
                                text = "Skip (Admin)",
                                onClick = { viewModel.adminSkipCheckpoint() },
                                variant = GccButtonVariant.SECONDARY,
                            )
                        }
                    }
                }
            }
        }

        // Floating header: TEST MODE badge + route name + timer + lap indicator
        uiState.route?.let { route ->
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp, start = 56.dp, end = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Amber TEST MODE badge
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = AmberBadge,
                ) {
                    Text(
                        "TEST MODE",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
                // Route info card
                GccCard(elevation = 4.dp) {
                    Text(
                        route.displayName,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            formatTimer(uiState.elapsedSeconds),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                        )
                        if (uiState.isRunning || uiState.isLapTransition) {
                            Text(
                                "Lap ${uiState.lap}/2",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = AmberBadgeDark,
                            )
                        }
                    }
                }
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
private fun LapTransitionPanel(
    lap1Seconds: Long,
    checkpointCount: Int,
    onStartLap2: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "Lap 1 Complete!",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "$checkpointCount checkpoints in ${formatTimer(lap1Seconds)}",
            style = MaterialTheme.typography.bodyLarge,
            color = TextMuted,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Lap 2 retraces the route for verification photos.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSubtle,
        )
        Spacer(Modifier.height(24.dp))
        GccButton(
            text = "Start Lap 2",
            onClick = onStartLap2,
            variant = GccButtonVariant.PRIMARY,
        )
    }
}

@Composable
private fun CompletionSummary(
    routeName: String,
    totalSeconds: Long,
    lap1Seconds: Long,
    checkpointCount: Int,
    onRunAgain: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Amber test badge
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = AmberBadge,
        ) {
            Text(
                "TEST COMPLETE",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            routeName,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(24.dp))

        GccCard {
            SummaryRow("Total Time", formatTimer(totalSeconds))
            Spacer(Modifier.height(8.dp))
            SummaryRow("Lap 1 (Before)", formatTimer(lap1Seconds))
            Spacer(Modifier.height(8.dp))
            SummaryRow("Lap 2 (Verify)", formatTimer(totalSeconds - lap1Seconds))
            Spacer(Modifier.height(8.dp))
            SummaryRow("Checkpoints per Lap", "$checkpointCount")
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                "No payout triggered. No job records created.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSubtle,
            )
        }

        Spacer(Modifier.height(24.dp))
        GccButton(
            text = "Run Again",
            onClick = onRunAgain,
            variant = GccButtonVariant.PRIMARY,
        )
        Spacer(Modifier.height(8.dp))
        GccButton(
            text = "Back to Route",
            onClick = onBack,
            variant = GccButtonVariant.OUTLINED,
        )
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextMuted)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun TestChecklistItem(
    index: Int,
    item: CheckpointUiItem,
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

private fun formatTimer(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return "${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
}
