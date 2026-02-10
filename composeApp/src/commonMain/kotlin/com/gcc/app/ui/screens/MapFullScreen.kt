package com.gcc.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.map.ChicagoOverlay
import com.gcc.app.ui.map.CommunityArea
import com.gcc.app.ui.map.GccMap
import com.gcc.app.ui.map.MapStyles
import com.gcc.app.ui.map.RouteOverlay
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
    }

    // Extract route checkpoint coordinates for polyline rendering
    val routeCoordinates: List<List<Coordinates>> = remember(uiState.routes) {
        uiState.routes.map { route ->
            route.checkpoints
                .sortedBy { it.sequenceOrder }
                .map { it.coordinates }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        GccMap(
            modifier = Modifier.fillMaxSize(),
            centerLat = MapStyles.CHICAGO_CENTER_LAT,
            centerLng = MapStyles.CHICAGO_CENTER_LNG,
            zoom = MapStyles.ZOOM_NEIGHBORHOOD,
            enableGestures = true,
            routeCoordinates = routeCoordinates,
            onAreaTap = { /* tooltip handled internally by GccMap */ },
        ) {
            ChicagoOverlay(
                modifier = Modifier.align(Alignment.TopStart),
            )
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
