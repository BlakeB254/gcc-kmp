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
import com.gcc.app.ui.map.CheckpointThumbnail
import com.gcc.app.ui.map.GccMap
import com.gcc.app.ui.map.MapStyles
import com.gcc.app.ui.theme.*
import com.gcc.app.viewmodel.CheckpointStatus
import com.gcc.app.viewmodel.RouteDetailViewModel
import com.gcc.app.util.fmt
import com.gcc.app.util.padTwo
import com.gcc.shared.model.Coordinates
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

    // Compute route path from checkpoints for polyline rendering
    val sortedCheckpoints = remember(route) {
        route.checkpoints.sortedBy { it.sequenceOrder }
    }
    // Prefer server geometry (full road-following path), fall back to checkpoint coords
    val routePath = remember(route) {
        val geomCoords = route.geometry?.coordinates?.map { point ->
            Coordinates(lng = point[0], lat = point[1])
        }
        listOf(geomCoords ?: sortedCheckpoints.map { it.coordinates })
    }

    // Collect all route points (prefer geometry, fall back to checkpoints)
    val allRoutePoints = remember(route) {
        val geomCoords = route.geometry?.coordinates?.map { point ->
            Coordinates(lng = point[0], lat = point[1])
        }
        geomCoords ?: sortedCheckpoints.map { it.coordinates }
    }

    // Compute bounding box + center from all route points
    val mapCenter = remember(allRoutePoints) {
        if (allRoutePoints.isNotEmpty()) {
            val avgLat = allRoutePoints.sumOf { it.lat } / allRoutePoints.size
            val avgLng = allRoutePoints.sumOf { it.lng } / allRoutePoints.size
            Coordinates(lng = avgLng, lat = avgLat)
        } else {
            Coordinates(lng = MapStyles.CHICAGO_CENTER_LNG, lat = MapStyles.CHICAGO_CENTER_LAT)
        }
    }

    // Compute zoom that fits the route extent with padding
    val mapZoom = remember(allRoutePoints) {
        if (allRoutePoints.size < 2) {
            MapStyles.ZOOM_ROUTE
        } else {
            val latSpan = allRoutePoints.maxOf { it.lat } - allRoutePoints.minOf { it.lat }
            val lngSpan = allRoutePoints.maxOf { it.lng } - allRoutePoints.minOf { it.lng }
            val maxSpan = maxOf(latSpan, lngSpan)
            // Approximate zoom: each zoom level halves the span; 0.004° ≈ zoom 16
            when {
                maxSpan < 0.002 -> 17.0
                maxSpan < 0.005 -> 16.0
                maxSpan < 0.01  -> 15.5
                maxSpan < 0.02  -> 15.0
                maxSpan < 0.04  -> 14.5
                maxSpan < 0.08  -> 14.0
                else            -> 13.0
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
        ) {
            // Hero map with route polyline, back button, and gradient overlay
            item {
                Box(modifier = Modifier.height(280.dp)) {
                    GccMap(
                        modifier = Modifier.fillMaxSize(),
                        centerLat = mapCenter.lat,
                        centerLng = mapCenter.lng,
                        zoom = mapZoom,
                        enableGestures = true,
                        routeCoordinates = routePath,
                        animateRoutes = true,
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
                    // Back button inside hero map Box so it isn't blocked by UIKitView
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .padding(8.dp)
                            .align(Alignment.TopStart)
                            .statusBarsPadding(),
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

            // Floating info card overlapping hero
            item {
                GccCard(
                    modifier = Modifier
                        .padding(horizontal = 24.dp)
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
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Checkpoint timeline with location thumbnails
            itemsIndexed(
                sortedCheckpoints,
                key = { _, cp -> cp.sequenceOrder },
            ) { index, checkpoint ->
                Row(
                    modifier = Modifier.padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Timeline marker + connector
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
                                    .height(56.dp)
                                    .background(DividerColor)
                            )
                        }
                    }

                    // Checkpoint info + location thumbnail
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
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

                        // Location reference thumbnail
                        CheckpointThumbnail(
                            type = checkpoint.type,
                            sequenceNumber = index + 1,
                            totalCheckpoints = sortedCheckpoints.size,
                            modifier = Modifier.size(55.dp),
                        )
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
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                val isClaimable = route.status == com.gcc.shared.model.RouteStatus.PUBLISHED
                GccButton(
                    text = if (isClaimable) "Claim Route" else "Not Available",
                    onClick = { viewModel.claimRoute(routeId) },
                    variant = GccButtonVariant.PRIMARY,
                    trailingText = if (isClaimable) payoutString else null,
                    enabled = isClaimable && !uiState.isClaiming,
                    isLoading = uiState.isClaiming,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Spacer(modifier = Modifier.height(24.dp))
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
