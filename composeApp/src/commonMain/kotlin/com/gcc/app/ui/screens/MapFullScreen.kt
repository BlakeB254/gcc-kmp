package com.gcc.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.map.CommunityInfoPanel
import com.gcc.app.ui.map.GccMap
import com.gcc.app.ui.map.MapMode
import com.gcc.app.ui.map.MapModeToggle
import com.gcc.app.ui.map.MapStyles
import com.gcc.app.ui.map.RouteOverlay
import com.gcc.app.ui.map.chicagoCommunityAreas
import com.gcc.app.ui.map.findCommunityAreaForLocation
import com.gcc.app.platform.LocationProvider
import com.gcc.app.ui.theme.*
import com.gcc.app.viewmodel.BountyViewModel
import com.gcc.app.viewmodel.DashboardViewModel
import com.gcc.shared.auth.TokenManager
import com.gcc.shared.model.Coordinates
import org.koin.compose.koinInject

@Composable
fun MapFullScreen(
    onBack: () -> Unit = {},
    onRouteClick: (Int) -> Unit = {},
) {
    val viewModel = koinInject<DashboardViewModel>()
    val uiState by viewModel.uiState.collectAsState()
    val bountyViewModel = koinInject<BountyViewModel>()
    val bountyState by bountyViewModel.uiState.collectAsState()
    val locationProvider = koinInject<LocationProvider>()
    val tokenManager = koinInject<TokenManager>()
    val isAdmin = remember { tokenManager.isAdmin() }

    // Wire real GPS location
    val currentLocation by locationProvider.currentLocation.collectAsState()

    LaunchedEffect(Unit) {
        locationProvider.startLocationUpdates()
        if (uiState.routes.isEmpty()) viewModel.loadDashboard()
        if (uiState.worker == null) viewModel.loadWorkerProfile()
        if (uiState.serviceAreaName == null) viewModel.loadServiceArea()
        if (uiState.spots.isEmpty()) viewModel.loadSpots()
        bountyViewModel.loadBounties()
    }

    DisposableEffect(Unit) {
        onDispose { locationProvider.stopLocationUpdates() }
    }

    // Dual-mode state
    var mapMode by remember { mutableStateOf(MapMode.MY_AREA) }

    // Extract route coordinates: prefer server geometry, fall back to checkpoint coords
    val routeCoordinates: List<List<Coordinates>> = remember(uiState.routes) {
        uiState.routes.map { route ->
            route.geometry?.coordinates?.map { point ->
                Coordinates(lng = point[0], lat = point[1])
            } ?: route.checkpoints
                .sortedBy { it.sequenceOrder }
                .map { it.coordinates }
        }
    }

    // Compute camera center from the service area bounds (North Lawndale)
    val serviceArea = remember {
        chicagoCommunityAreas.find { it.isServiceArea }
    }
    val myAreaCenterLat = serviceArea?.let { (it.bounds.north + it.bounds.south) / 2.0 }
        ?: MapStyles.CHICAGO_CENTER_LAT
    val myAreaCenterLng = serviceArea?.let { (it.bounds.east + it.bounds.west) / 2.0 }
        ?: MapStyles.CHICAGO_CENTER_LNG

    // Camera center and zoom depend on mode
    val centerLat = if (mapMode == MapMode.MY_AREA) myAreaCenterLat else MapStyles.CHICAGO_CENTER_LAT
    val centerLng = if (mapMode == MapMode.MY_AREA) myAreaCenterLng else MapStyles.CHICAGO_CENTER_LNG
    val mapZoom = if (mapMode == MapMode.MY_AREA) MapStyles.ZOOM_SERVICE_AREA else MapStyles.ZOOM_CITY

    // GPS-gated visibility: admin sees all data globally,
    // normal users see only their current community area
    val currentGpsArea = remember(currentLocation) {
        currentLocation?.let { findCommunityAreaForLocation(it.lat, it.lng) }
    }
    val visibleBounties = remember(bountyState.bounties, mapMode, currentGpsArea, isAdmin) {
        when {
            mapMode == MapMode.MY_AREA -> emptyList()
            isAdmin -> bountyState.bounties // admin sees all bounties globally
            else -> {
                val area = currentGpsArea
                if (area != null) {
                    bountyState.bounties.filter { bounty ->
                        bounty.communityArea == area.name ||
                        (bounty.communityArea == null &&
                            bounty.locationLat in area.bounds.south..area.bounds.north &&
                            bounty.locationLng in area.bounds.west..area.bounds.east)
                    }
                } else {
                    emptyList() // no GPS fix = no bounties for normal users
                }
            }
        }
    }
    val visibleSpots = remember(uiState.spots, mapMode, currentGpsArea, isAdmin) {
        when {
            mapMode == MapMode.MY_AREA -> uiState.spots // show all spots in service area view
            isAdmin -> uiState.spots // admin sees all spots globally
            else -> {
                val area = currentGpsArea
                if (area != null) {
                    uiState.spots.filter { spot ->
                        spot.coordinates.lat in area.bounds.south..area.bounds.north &&
                        spot.coordinates.lng in area.bounds.west..area.bounds.east
                    }
                } else {
                    emptyList()
                }
            }
        }
    }

    // Worker info for the panel
    val workerName = uiState.worker?.name?.split(" ")?.firstOrNull()
    val areaName = uiState.serviceAreaName
        ?: serviceArea?.displayName?.replace("\n", " ")
        ?: "North Lawndale"
    val totalRoutes = uiState.routes.size
    val jobsCompleted = uiState.worker?.stats?.jobsCompleted ?: 0
    val totalEarnings = uiState.worker?.stats?.totalEarnings ?: 0

    Box(modifier = Modifier.fillMaxSize()) {
        GccMap(
            modifier = Modifier.fillMaxSize(),
            centerLat = centerLat,
            centerLng = centerLng,
            zoom = mapZoom,
            enableGestures = true,
            showControls = true,
            statusBarPadding = true,
            routeCoordinates = if (mapMode == MapMode.MY_AREA) routeCoordinates else emptyList(),
            onAreaTap = { /* tooltip handled internally by GccMap */ },
            onRouteTap = if (mapMode == MapMode.MY_AREA) { routeIndex ->
                uiState.routes.getOrNull(routeIndex)?.let { route ->
                    onRouteClick(route.id)
                }
            } else null,
            cameraBounds = if (mapMode == MapMode.MY_AREA) serviceArea?.bounds else null,
            bounties = visibleBounties,
            spots = visibleSpots,
            mapMode = mapMode,
        ) {
            // Mode toggle (centered near top)
            MapModeToggle(
                currentMode = mapMode,
                onModeChange = { mapMode = it },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 8.dp),
            )

            // Community info panel (top-left, below mode toggle) — only in My Area
            if (mapMode == MapMode.MY_AREA) {
                CommunityInfoPanel(
                    workerName = workerName,
                    communityAreaName = areaName,
                    totalRoutes = totalRoutes,
                    registeredRoutes = 0,
                    jobsCompleted = jobsCompleted,
                    totalEarnings = totalEarnings,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(top = 52.dp),
                )
            }

            // Floating close button (top-right, below status bar)
            TextButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 5.dp, end = 4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.9f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "\u2715",
                        style = MaterialTheme.typography.titleMedium,
                        color = GccGreenDark,
                    )
                }
            }

            // Route chips at the bottom — only in My Area mode
            if (mapMode == MapMode.MY_AREA && uiState.routes.isNotEmpty()) {
                RouteOverlay(
                    routes = uiState.routes,
                    onRouteClick = { route -> onRouteClick(route.id) },
                    modifier = Modifier.align(Alignment.BottomStart),
                )
            }
        }
    }
}
