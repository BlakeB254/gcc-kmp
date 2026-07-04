package com.gcc.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.components.*
import com.gcc.app.util.padTwo
import com.gcc.app.platform.LocationProvider
import com.gcc.app.ui.map.GccMap
import com.gcc.app.ui.map.findCommunityAreaForLocation
import com.gcc.app.ui.map.MapStyles
import com.gcc.app.ui.theme.*
import com.gcc.app.viewmodel.RouteDetailViewModel
import com.gcc.app.util.fmt
import com.gcc.shared.model.Coordinates
import com.gcc.shared.model.Route
import com.gcc.shared.model.RouteStatus
import com.gcc.shared.model.VerificationStatus
import com.gcc.shared.model.Worker
import org.koin.compose.koinInject

@Composable
fun RouteDetailScreen(
    routeId: Int = 0,
    allRouteIds: List<Int> = emptyList(),
    onBack: () -> Unit = {},
    onStartJob: (jobId: Int) -> Unit = {},
    onNavigateToRoute: (routeId: Int) -> Unit = {},
    onStartScan: (routeId: Int) -> Unit = {},
    onVerificationGate: (actionLabel: String) -> Unit = {},
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
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Route not found", style = MaterialTheme.typography.titleMedium)
                    uiState.error?.let { error ->
                        Text(error, style = MaterialTheme.typography.bodySmall, color = GccError)
                    }
                    GccButton(
                        text = "Go Back",
                        onClick = onBack,
                        variant = GccButtonVariant.OUTLINED,
                    )
                }
            }
        }
        return
    }

    val payoutString = route.payoutAmount?.let { cents ->
        "$${cents / 100}" + if (cents % 100 != 0) ".${(cents % 100).padTwo()}" else ""
    }
    val authorizationState = remember(route, uiState.worker) {
        routeAuthorizationState(route, uiState.worker)
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

    // Shift map center upward so the route sits above the bottom panel
    val mapCenter = remember(allRoutePoints, panelHeightPx, totalHeightPx) {
        if (allRoutePoints.isEmpty()) {
            return@remember Coordinates(lng = MapStyles.NORTH_LAWNDALE_CENTER_LNG, lat = MapStyles.NORTH_LAWNDALE_CENTER_LAT)
        }
        val avgLat = allRoutePoints.sumOf { it.lat } / allRoutePoints.size
        val avgLng = allRoutePoints.sumOf { it.lng } / allRoutePoints.size

        if (totalHeightPx <= 0 || panelHeightPx <= 0) {
            Coordinates(lng = avgLng, lat = avgLat)
        } else {
            // Fixed shift: move center down by half the panel's share of latitude
            // At zoom ~15-16, 1 degree lat ≈ 111km, screen shows ~0.01-0.02 degrees
            val panelFraction = panelHeightPx.toDouble() / totalHeightPx
            // Shift = panelFraction * half the visible lat span (approximated from zoom)
            val latShift = panelFraction * 0.003
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
        // Collect image location stars from route
        val imageStars = remember(route) {
            val lat = route.imageLocationLat
            val lng = route.imageLocationLng
            if (lat != null && lng != null) listOf(Coordinates(lng = lng, lat = lat))
            else emptyList()
        }

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
            imageStars = imageStars,
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

        // Bottom detail panel — content-sized, pinned to bottom
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = with(LocalDensity.current) { (totalHeightPx * 0.45f).toDp() })
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
                // Cover image
                if (route.imageUrl != null) {
                    item {
                        NetworkImage(
                            url = route.imageUrl!!,
                            contentDescription = route.displayName,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(140.dp)
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            contentScale = ContentScale.Crop,
                            decodeWidth = 800,
                            decodeHeight = 400,
                        )
                    }
                }

                // Route info card
                item {
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                        Text(
                            route.displayName,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        )
                        if (route.description != null) {
                            Text(
                                route.description!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                            )
                        } else {
                            Text(
                                "Code: ${route.code}",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSubtle,
                            )
                        }
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

                    Spacer(modifier = Modifier.height(8.dp))
                    HeatBadge(
                        label = "Area Score",
                        score = uiState.areaCleanlinessScore,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        color = DividerColor,
                    )
                }

                // (Checkpoint list removed: checkpoints are photo-era legacy —
                // scan fulfillment runs on the block boundary corridor. The
                // sorted list above still feeds the map path fallback.)

                // Route status and actions
                item {
                    Spacer(modifier = Modifier.height(12.dp))

                    // Admin availability notice
                    Text(
                        "Routes are released by admins when cleanup work is ready.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    RouteAuthorizationBanner(
                        state = authorizationState,
                        onVerifyClick = { onVerificationGate("claim this route") },
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    GccButton(
                        text = if (route.isWorkerAvailableRoute()) "Scout & Clean" else "Not Available",
                        onClick = { onStartScan(routeId) },
                        variant = GccButtonVariant.PRIMARY,
                        enabled = authorizationState.canStartRoute,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
            }
        }
    }
}

private val RouteAuthState.canStartRoute: Boolean
    get() = this is RouteAuthState.Authorized || this is RouteAuthState.Unknown

private fun routeAuthorizationState(route: Route, worker: Worker?): RouteAuthState {
    if (!route.isWorkerAvailableRoute()) {
        return RouteAuthState.RouteUnavailable
    }

    if (worker?.verification?.status != VerificationStatus.APPROVED) {
        return RouteAuthState.VerificationRequired
    }

    val routeArea = routeCommunityAreaName(route) ?: return RouteAuthState.Unknown
    val workerNeighborhood = worker.address.neighborhood
    if (worker.address.isNorthLawndale == true && routeArea.normalizedZone() == "north lawndale") {
        return RouteAuthState.Authorized
    }

    if (workerNeighborhood.isNullOrBlank()) {
        return RouteAuthState.Unknown
    }

    return if (workerNeighborhood.normalizedZone() == routeArea.normalizedZone()) {
        RouteAuthState.Authorized
    } else {
        RouteAuthState.OutsideServiceArea(
            workerArea = workerNeighborhood,
            routeArea = routeArea,
        )
    }
}

private fun Route.isWorkerAvailableRoute(): Boolean =
    status == RouteStatus.PUBLISHED && checkpoints.size >= 2

private fun routeCommunityAreaName(route: Route): String? {
    val routePoints = route.geometry?.coordinates?.mapNotNull { point ->
        val lng = point.getOrNull(0)
        val lat = point.getOrNull(1)
        if (lat != null && lng != null) Coordinates(lng = lng, lat = lat) else null
    } ?: route.checkpoints.map { it.coordinates }

    val area = routePoints
        .asSequence()
        .mapNotNull { point -> findCommunityAreaForLocation(point.lat, point.lng) }
        .firstOrNull()

    return area?.displayName?.replace('\n', ' ') ?: area?.name?.replace('_', ' ')
}

private fun String.normalizedZone(): String =
    trim()
        .lowercase()
        .replace('_', ' ')
        .replace(Regex("\\s+"), " ")

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
