package com.gcc.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.components.*
import com.gcc.app.ui.map.CheckpointMarker
import com.gcc.app.ui.map.GccMap
import com.gcc.app.ui.map.MapStyles
import com.gcc.app.ui.theme.*
import com.gcc.app.viewmodel.CheckpointStatus
import com.gcc.app.viewmodel.RouteDetailViewModel
import com.gcc.app.util.fmt
import com.gcc.app.util.padTwo
import org.koin.compose.koinInject

@Composable
fun RouteDetailScreen(
    routeId: Int = 0,
    onBack: () -> Unit = {},
    onStartJob: (jobId: Int) -> Unit = {},
) {
    val viewModel = koinInject<RouteDetailViewModel>()
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(routeId) {
        viewModel.loadRoute(routeId)
    }

    // Navigate to ActiveJob on successful claim
    LaunchedEffect(uiState.claimedJobId) {
        uiState.claimedJobId?.let { jobId ->
            onStartJob(jobId)
        }
    }

    val route = uiState.route

    if (route == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(color = GccGreen)
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Route not found")
                    uiState.error?.let { error ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(error, style = MaterialTheme.typography.bodySmall, color = GccError)
                    }
                }
            }
        }
        return
    }

    val payoutString = route.payoutAmount?.let { cents ->
        "$${cents / 100}.${(cents % 100).padTwo()}"
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
        ) {
            // Hero map with gradient overlay
            item {
                Box(modifier = Modifier.height(240.dp)) {
                    val firstCheckpoint = route.checkpoints.firstOrNull()
                    GccMap(
                        modifier = Modifier.fillMaxSize(),
                        centerLat = firstCheckpoint?.coordinates?.lat ?: MapStyles.CHICAGO_CENTER_LAT,
                        centerLng = firstCheckpoint?.coordinates?.lng ?: MapStyles.CHICAGO_CENTER_LNG,
                        zoom = MapStyles.ZOOM_ROUTE,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                            .align(Alignment.BottomCenter)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, MaterialTheme.colorScheme.surface)
                                )
                            )
                    )
                }
            }

            // Floating info card overlapping hero
            item {
                GccCard(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .offset(y = (-24).dp),
                    elevation = 6.dp,
                ) {
                    Text(
                        route.displayName,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    )
                    Text(
                        "Code: ${route.code}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSubtle,
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        route.distanceMeters?.let { meters ->
                            InfoStat(
                                value = if (meters >= 1000) "${(meters / 1000).fmt(1)} km"
                                else "${meters.toInt()} m",
                                label = "Distance",
                            )
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(32.dp)
                                    .background(DividerColor)
                            )
                        }
                        route.estimatedDurationMinutes?.let { mins ->
                            InfoStat(value = "$mins min", label = "Duration")
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(32.dp)
                                    .background(DividerColor)
                            )
                        }
                        payoutString?.let {
                            InfoStat(value = it, label = "Payout", highlight = true)
                        }
                    }
                }
            }

            // Checkpoint timeline header
            item {
                SectionHeader(
                    title = "Checkpoints (${route.checkpoints.size})",
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Checkpoint timeline
            val sortedCheckpoints = route.checkpoints.sortedBy { it.sequenceOrder }
            itemsIndexed(
                sortedCheckpoints,
                key = { _, cp -> cp.sequenceOrder },
            ) { index, checkpoint ->
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CheckpointMarker(
                            sequenceNumber = index + 1,
                            type = checkpoint.type,
                            status = CheckpointStatus.UPCOMING,
                            photoRequired = checkpoint.photoRequired,
                        )
                        if (index < sortedCheckpoints.size - 1) {
                            Box(
                                modifier = Modifier
                                    .width(2.dp)
                                    .height(32.dp)
                                    .background(DividerColor)
                            )
                        }
                    }
                    Column(modifier = Modifier.padding(top = 4.dp)) {
                        Text(
                            checkpoint.name,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            checkpoint.type.name.replace("_", " "),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                        )
                        if (checkpoint.photoRequired) {
                            Text(
                                "\uD83D\uDCF7 Photo required",
                                style = MaterialTheme.typography.bodySmall,
                                color = GccGreen,
                            )
                        }
                    }
                }
            }

            // Claim route button
            item {
                Spacer(modifier = Modifier.height(16.dp))

                uiState.claimError?.let { error ->
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = GccError,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                GccButton(
                    text = "Claim Route",
                    onClick = { viewModel.claimRoute(routeId) },
                    variant = GccButtonVariant.PRIMARY,
                    trailingText = payoutString,
                    enabled = !uiState.isClaiming,
                    isLoading = uiState.isClaiming,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(modifier = Modifier.height(24.dp))
            }
        }

        // Floating back button
        TextButton(
            onClick = onBack,
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
private fun InfoStat(
    value: String,
    label: String,
    highlight: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = if (highlight) GccGreen else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
        )
    }
}
