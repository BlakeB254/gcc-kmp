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
import com.gcc.app.ui.map.MapStyles
import com.gcc.app.ui.map.RouteOverlay
import com.gcc.app.ui.map.chicagoCommunityAreas
import com.gcc.app.ui.theme.*
import com.gcc.app.viewmodel.DashboardViewModel
import com.gcc.shared.model.Coordinates
import org.koin.compose.koinInject

@Composable
fun MapFullScreen(
    onBack: () -> Unit = {},
    onRouteClick: (Int) -> Unit = {},
) {
    val viewModel = koinInject<DashboardViewModel>()
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        if (uiState.routes.isEmpty()) viewModel.loadDashboard()
        if (uiState.worker == null) viewModel.loadWorkerProfile()
        if (uiState.serviceAreaName == null) viewModel.loadServiceArea()
    }

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
    val centerLat = serviceArea?.let { (it.bounds.north + it.bounds.south) / 2.0 }
        ?: MapStyles.CHICAGO_CENTER_LAT
    val centerLng = serviceArea?.let { (it.bounds.east + it.bounds.west) / 2.0 }
        ?: MapStyles.CHICAGO_CENTER_LNG

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
            zoom = MapStyles.ZOOM_SERVICE_AREA,
            enableGestures = true,
            showControls = true,
            statusBarPadding = true,
            routeCoordinates = routeCoordinates,
            onAreaTap = { /* tooltip handled internally by GccMap */ },
        ) {
            // Community info panel (top-left, below map controls)
            CommunityInfoPanel(
                workerName = workerName,
                communityAreaName = areaName,
                totalRoutes = totalRoutes,
                registeredRoutes = 0, // TODO: wire to actual registered count when API supports it
                jobsCompleted = jobsCompleted,
                totalEarnings = totalEarnings,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(top = 42.dp),
            )

            // Route chips at the bottom
            if (uiState.routes.isNotEmpty()) {
                RouteOverlay(
                    routes = uiState.routes,
                    onRouteClick = { route -> onRouteClick(route.id) },
                    modifier = Modifier.align(Alignment.BottomStart),
                )
            }
        }

        // Floating close button (top-right)
        TextButton(
            onClick = onBack,
            modifier = Modifier
                .statusBarsPadding()
                .padding(8.dp)
                .align(Alignment.TopEnd),
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
    }
}
