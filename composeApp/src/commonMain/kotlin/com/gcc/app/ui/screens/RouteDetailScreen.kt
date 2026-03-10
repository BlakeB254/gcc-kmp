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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.components.*
import com.gcc.app.util.padTwo
import com.gcc.app.platform.LocationProvider
import com.gcc.app.ui.map.CheckpointMarker
import com.gcc.app.ui.map.CheckpointThumbnail
import com.gcc.app.ui.map.GccMap
import com.gcc.app.ui.map.MapStyles
import com.gcc.app.ui.theme.*
import com.gcc.app.viewmodel.CheckpointStatus
import com.gcc.app.viewmodel.RouteDetailViewModel
import com.gcc.app.util.fmt
import com.gcc.shared.model.Coordinates
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import org.koin.compose.koinInject

@Composable
fun RouteDetailScreen(
    routeId: Int = 0,
    allRouteIds: List<Int> = emptyList(),
    onBack: () -> Unit = {},
    onStartJob: (jobId: Int) -> Unit = {},
    onNavigateToRoute: (routeId: Int) -> Unit = {},
) {
    val viewModel = koinInject<RouteDetailViewModel>()
    val locationProvider = koinInject<LocationProvider>()
    val uiState by viewModel.uiState.collectAsState()
    val currentLocation by locationProvider.currentLocation.collectAsState()
    LaunchedEffect(Unit) {
        locationProvider.startLocationUpdates()
    }

    DisposableEffect(Unit) {
        onDispose { locationProvider.stopLocationUpdates() }
    }

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
        "$${cents / 100}" + if (cents % 100 != 0) ".${(cents % 100).padTwo()}" else ""
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

    // Track the bottom panel height so we can offset the map center
    var panelHeightPx by remember { mutableStateOf(0) }
    var totalHeightPx by remember { mutableStateOf(0) }

    // Shift map center upward so the route is centered in the visible area above the panel
    val mapCenter = remember(allRoutePoints, panelHeightPx, totalHeightPx) {
        if (allRoutePoints.isEmpty()) {
            return@remember Coordinates(lng = MapStyles.CHICAGO_CENTER_LNG, lat = MapStyles.CHICAGO_CENTER_LAT)
        }
        val avgLat = allRoutePoints.sumOf { it.lat } / allRoutePoints.size
        val avgLng = allRoutePoints.sumOf { it.lng } / allRoutePoints.size

        if (totalHeightPx <= 0 || panelHeightPx <= 0) {
            Coordinates(lng = avgLng, lat = avgLat)
        } else {
            // Shift center down (in latitude space, higher latitude = higher on screen)
            // so the route appears centered in the visible map area above the panel
            val panelFraction = panelHeightPx.toDouble() / totalHeightPx
            val latSpan = if (allRoutePoints.size >= 2) {
                allRoutePoints.maxOf { it.lat } - allRoutePoints.minOf { it.lat }
            } else 0.005
            // Shift by half the panel fraction * a proportional lat offset
            val latShift = latSpan * panelFraction * 0.6
            Coordinates(lng = avgLng, lat = avgLat - latShift)
        }
    }

    // Compute zoom that fits the route in the visible map area (above panel)
    val mapZoom = remember(allRoutePoints, panelHeightPx, totalHeightPx) {
        if (allRoutePoints.size < 2) {
            MapStyles.ZOOM_ROUTE
        } else {
            val latSpan = allRoutePoints.maxOf { it.lat } - allRoutePoints.minOf { it.lat }
            val lngSpan = allRoutePoints.maxOf { it.lng } - allRoutePoints.minOf { it.lng }

            // Account for reduced visible height due to panel
            val visibleFraction = if (totalHeightPx > 0 && panelHeightPx > 0) {
                1.0 - (panelHeightPx.toDouble() / totalHeightPx) * 0.7
            } else 0.55 // default: assume panel covers ~45% of screen

            // Need more zoom-out to fit route in smaller visible area
            val latEffective = latSpan / visibleFraction
            val effectiveSpan = maxOf(latEffective, lngSpan * 1.2)
            when {
                effectiveSpan < 0.002 -> 16.5
                effectiveSpan < 0.004 -> 16.0
                effectiveSpan < 0.007 -> 15.5
                effectiveSpan < 0.01  -> 15.0
                effectiveSpan < 0.02  -> 14.5
                effectiveSpan < 0.04  -> 14.0
                effectiveSpan < 0.08  -> 13.5
                else                  -> 12.5
            }
        }
    }

    // Route navigation: find prev/next in allRouteIds
    val currentIdx = allRouteIds.indexOf(routeId)
    val prevRouteId = if (currentIdx > 0) allRouteIds[currentIdx - 1] else null
    val nextRouteId = if (currentIdx in 0 until allRouteIds.size - 1) allRouteIds[currentIdx + 1] else null

    // Full-screen layout: map behind, compact panel pinned at bottom
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { totalHeightPx = it.height }
            .pointerInput(prevRouteId, nextRouteId) {
                var totalDrag = 0f
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (totalDrag < -100f && nextRouteId != null) {
                            onNavigateToRoute(nextRouteId)
                        } else if (totalDrag > 100f && prevRouteId != null) {
                            onNavigateToRoute(prevRouteId)
                        }
                        totalDrag = 0f
                    },
                    onDragCancel = { totalDrag = 0f },
                    onHorizontalDrag = { _, dragAmount -> totalDrag += dragAmount },
                )
            },
    ) {
        // Full-screen map
        GccMap(
            modifier = Modifier.fillMaxSize(),
            centerLat = mapCenter.lat,
            centerLng = mapCenter.lng,
            zoom = mapZoom,
            currentLocation = currentLocation,
            enableGestures = true,
            routeCoordinates = routePath,
            animateRoutes = true,
        )

        // Top nav: back + prev/next route arrows
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.9f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("\u2190", style = MaterialTheme.typography.titleMedium, color = GccGreenDark)
                }
            }

            if (allRouteIds.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(
                        onClick = { prevRouteId?.let { onNavigateToRoute(it) } },
                        enabled = prevRouteId != null,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = if (prevRouteId != null) 0.9f else 0.4f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("\u25C0", style = MaterialTheme.typography.bodyLarge, color = GccGreenDark)
                        }
                    }
                    IconButton(
                        onClick = { nextRouteId?.let { onNavigateToRoute(it) } },
                        enabled = nextRouteId != null,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = if (nextRouteId != null) 0.9f else 0.4f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("\u25B6", style = MaterialTheme.typography.bodyLarge, color = GccGreenDark)
                        }
                    }
                }
            }
        }

        // Bottom detail panel — compact, max 40% of screen
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.40f)
                .onSizeChanged { panelHeightPx = it.height }
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(MaterialTheme.colorScheme.surface),
        ) {
            // Drag handle
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .align(Alignment.CenterHorizontally)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(DividerColor),
            )

            // Scrollable content within the panel
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                // Route info card
                item {
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                        Text(
                            route.displayName,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        )
                        Text(
                            "Code: ${route.code}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSubtle,
                        )
                        Spacer(modifier = Modifier.height(10.dp))

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
                                        .height(28.dp)
                                        .background(DividerColor)
                                )
                            }
                            route.estimatedDurationMinutes?.let { mins ->
                                InfoStat(value = "$mins min", label = "Duration")
                                Box(
                                    modifier = Modifier
                                        .width(1.dp)
                                        .height(28.dp)
                                        .background(DividerColor)
                                )
                            }
                            payoutString?.let {
                                InfoStat(value = it, label = "Payout", highlight = true)
                            }
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        color = DividerColor,
                    )
                }

                // Checkpoint list (compact)
                if (sortedCheckpoints.isNotEmpty()) {
                    item {
                        Text(
                            "Checkpoints (${sortedCheckpoints.size})",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    itemsIndexed(
                        sortedCheckpoints,
                        key = { _, cp -> cp.sequenceOrder },
                    ) { index, checkpoint ->
                        Row(
                            modifier = Modifier.padding(horizontal = 20.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
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
                                            .height(40.dp)
                                            .background(DividerColor)
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(top = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        checkpoint.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        checkpoint.type.name.replace("_", " "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextMuted,
                                    )
                                }

                                CheckpointThumbnail(
                                    type = checkpoint.type,
                                    sequenceNumber = index + 1,
                                    totalCheckpoints = sortedCheckpoints.size,
                                    modifier = Modifier.size(44.dp),
                                )
                            }
                        }
                    }
                }

                // Claim route button
                item {
                    Spacer(modifier = Modifier.height(12.dp))

                    uiState.claimError?.let { error ->
                        Text(
                            error,
                            style = MaterialTheme.typography.bodySmall,
                            color = GccError,
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    val isClaimable = route.status == com.gcc.shared.model.RouteStatus.PUBLISHED
                    GccButton(
                        text = if (isClaimable) "Claim Route" else "Not Claimable",
                        onClick = { viewModel.claimRoute(routeId) },
                        variant = GccButtonVariant.PRIMARY,
                        trailingText = if (isClaimable) payoutString else null,
                        enabled = isClaimable && !uiState.isClaiming,
                        isLoading = uiState.isClaiming,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
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
