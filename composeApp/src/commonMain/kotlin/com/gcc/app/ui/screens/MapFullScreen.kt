package com.gcc.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import com.gcc.app.ui.map.CommunityInfoPanel
import com.gcc.app.ui.map.GccMap
import com.gcc.app.ui.map.MapMode
import com.gcc.app.ui.map.MapModeToggle
import com.gcc.app.ui.map.MapStyles
import com.gcc.app.ui.map.RouteOverlay
import com.gcc.app.ui.map.chicagoCommunityAreas
import com.gcc.app.ui.map.findCommunityAreaForLocation
import com.gcc.app.platform.LocationProvider
import com.gcc.app.ui.components.PhotoStrip
import com.gcc.app.ui.theme.*
import com.gcc.app.viewmodel.BountyViewModel
import com.gcc.app.viewmodel.DashboardViewModel
import com.gcc.shared.auth.TokenManager
import com.gcc.shared.model.CommunityReport
import com.gcc.shared.model.Coordinates
import kotlin.math.ln
import org.koin.compose.koinInject

@Composable
fun MapFullScreen(
    onBack: () -> Unit = {},
    onRouteClick: (Int) -> Unit = {},
    onBountyClick: (Int) -> Unit = {},
    onVolunteerCleanup: (reportId: Int) -> Unit = {},
    onSpotVerification: (reportId: Int) -> Unit = {},
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
        // Always refresh spots (admin may have deleted/updated them)
        viewModel.loadSpots()
        bountyViewModel.loadBounties()
    }

    DisposableEffect(Unit) {
        onDispose { locationProvider.stopLocationUpdates() }
    }

    // Dual-mode state
    var mapMode by remember { mutableStateOf(MapMode.MY_AREA) }

    // Selected spot for info card
    var selectedSpot by remember { mutableStateOf<CommunityReport?>(null) }

    // Camera override for zooming to a tapped spot/bounty/route
    var overrideCenterLat by remember { mutableStateOf<Double?>(null) }
    var overrideCenterLng by remember { mutableStateOf<Double?>(null) }
    var overrideZoom by remember { mutableStateOf<Double?>(null) }

    // Selected route for zoom-to-fit before navigation
    var selectedRouteForZoom by remember { mutableStateOf<com.gcc.shared.model.Route?>(null) }

    // Navigate to route detail after brief zoom animation
    LaunchedEffect(selectedRouteForZoom) {
        val route = selectedRouteForZoom ?: return@LaunchedEffect
        kotlinx.coroutines.delay(800) // Let map animate to show full route
        onRouteClick(route.id)
        selectedRouteForZoom = null
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
    val myAreaCenterLat = serviceArea?.let { (it.bounds.north + it.bounds.south) / 2.0 }
        ?: MapStyles.CHICAGO_CENTER_LAT
    val myAreaCenterLng = serviceArea?.let { (it.bounds.east + it.bounds.west) / 2.0 }
        ?: MapStyles.CHICAGO_CENTER_LNG

    // Camera center and zoom depend on mode (overridden when tapping a spot/bounty)
    val centerLat = overrideCenterLat ?: if (mapMode == MapMode.MY_AREA) myAreaCenterLat else MapStyles.CHICAGO_CENTER_LAT
    val centerLng = overrideCenterLng ?: if (mapMode == MapMode.MY_AREA) myAreaCenterLng else MapStyles.CHICAGO_CENTER_LNG
    val mapZoom = overrideZoom ?: if (mapMode == MapMode.MY_AREA) MapStyles.ZOOM_SERVICE_AREA else MapStyles.ZOOM_CITY

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
            currentLocation = currentLocation,
            enableGestures = true,
            showControls = true,
            statusBarPadding = true,
            routeCoordinates = if (mapMode == MapMode.MY_AREA) routeCoordinates else emptyList(),
            onAreaTap = { /* tooltip handled internally by GccMap */ },
            onRouteTap = if (mapMode == MapMode.MY_AREA) { routeIndex ->
                uiState.routes.getOrNull(routeIndex)?.let { route ->
                    // Zoom to route bounds first, then navigate
                    val allPts = route.geometry?.coordinates?.map {
                        Coordinates(lng = it[0], lat = it[1])
                    } ?: route.checkpoints.sortedBy { it.sequenceOrder }.map { it.coordinates }
                    if (allPts.size >= 2) {
                        val minLat = allPts.minOf { it.lat }
                        val maxLat = allPts.maxOf { it.lat }
                        val minLng = allPts.minOf { it.lng }
                        val maxLng = allPts.maxOf { it.lng }
                        overrideCenterLat = (minLat + maxLat) / 2.0
                        overrideCenterLng = (minLng + maxLng) / 2.0
                        val latSpan = (maxLat - minLat) * 1.3
                        val lngSpan = (maxLng - minLng) * 1.3
                        val latZoom = if (latSpan > 0) ln(360.0 / latSpan) / ln(2.0) else 16.0
                        val lngZoom = if (lngSpan > 0) ln(360.0 / lngSpan) / ln(2.0) else 16.0
                        overrideZoom = minOf(latZoom, lngZoom).coerceIn(12.0, 17.0)
                    }
                    selectedRouteForZoom = route
                }
            } else null,
            onSpotTap = { spotId ->
                val spot = (uiState.spots).find { it.id == spotId }
                if (spot != null) {
                    selectedSpot = spot
                    overrideCenterLat = spot.coordinates.lat
                    overrideCenterLng = spot.coordinates.lng
                    overrideZoom = MapStyles.ZOOM_ROUTE
                }
            },
            onBountyTap = { bountyId ->
                onBountyClick(bountyId)
            },
            cameraBounds = if (mapMode == MapMode.MY_AREA && overrideCenterLat == null) serviceArea?.bounds else null,
            bounties = visibleBounties,
            spots = visibleSpots,
            mapMode = mapMode,
        ) {
            // Mode toggle (centered near top)
            MapModeToggle(
                currentMode = mapMode,
                onModeChange = {
                    mapMode = it
                    // Clear overrides when switching modes
                    overrideCenterLat = null
                    overrideCenterLng = null
                    overrideZoom = null
                    selectedSpot = null
                },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 8.dp),
            )

            // Community info panel (top-left, below mode toggle) — only in My Area
            if (mapMode == MapMode.MY_AREA && selectedSpot == null) {
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

            // Route chips at the bottom — only in My Area mode, hidden when spot selected
            if (mapMode == MapMode.MY_AREA && uiState.routes.isNotEmpty() && selectedSpot == null) {
                RouteOverlay(
                    routes = uiState.routes,
                    onRouteClick = { route ->
                        // Zoom to fit the full route path, then navigate after a brief pause
                        val allPts = route.geometry?.coordinates?.map {
                            Coordinates(lng = it[0], lat = it[1])
                        } ?: route.checkpoints.sortedBy { it.sequenceOrder }.map { it.coordinates }
                        if (allPts.size >= 2) {
                            val minLat = allPts.minOf { it.lat }
                            val maxLat = allPts.maxOf { it.lat }
                            val minLng = allPts.minOf { it.lng }
                            val maxLng = allPts.maxOf { it.lng }
                            overrideCenterLat = (minLat + maxLat) / 2.0
                            overrideCenterLng = (minLng + maxLng) / 2.0
                            val latSpan = (maxLat - minLat) * 1.3
                            val lngSpan = (maxLng - minLng) * 1.3
                            val latZoom = if (latSpan > 0) ln(360.0 / latSpan) / ln(2.0) else 16.0
                            val lngZoom = if (lngSpan > 0) ln(360.0 / lngSpan) / ln(2.0) else 16.0
                            overrideZoom = minOf(latZoom, lngZoom).coerceIn(12.0, 17.0)
                        }
                        selectedRouteForZoom = route
                    },
                    modifier = Modifier.align(Alignment.BottomStart),
                )
            }

            // Spot info card — shown when a spot is tapped
            selectedSpot?.let { spot ->
                SpotInfoCard(
                    spot = spot,
                    onDismiss = {
                        selectedSpot = null
                        overrideCenterLat = null
                        overrideCenterLng = null
                        overrideZoom = null
                    },
                    onVolunteerCleanup = {
                        onVolunteerCleanup(spot.id)
                        selectedSpot = null
                    },
                    onVerifySpot = {
                        onSpotVerification(spot.id)
                        selectedSpot = null
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp, start = 16.dp, end = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun SpotInfoCard(
    spot: CommunityReport,
    onDismiss: () -> Unit,
    onVolunteerCleanup: () -> Unit = {},
    onVerifySpot: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val statusColor = when (spot.status) {
        com.gcc.shared.model.CommunityReportStatus.PENDING -> Color(0xFFFF9800)
        com.gcc.shared.model.CommunityReportStatus.VERIFIED -> GccGreen
        com.gcc.shared.model.CommunityReportStatus.RESOLVED -> GccGold
        com.gcc.shared.model.CommunityReportStatus.ACKNOWLEDGED -> Color(0xFF2196F3)
        com.gcc.shared.model.CommunityReportStatus.REJECTED -> Color(0xFFFF5252)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    spot.type.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = statusColor.copy(alpha = 0.15f),
                    ) {
                        Text(
                            spot.status.name.lowercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                    TextButton(
                        onClick = onDismiss,
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.size(24.dp),
                    ) {
                        Text("\u2715", color = TextMuted)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Photos
            if (spot.mediaUrls.isNotEmpty()) {
                PhotoStrip(urls = spot.mediaUrls)
                Spacer(modifier = Modifier.height(8.dp))
            }

            spot.description?.let { desc ->
                Text(
                    desc,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted,
                    maxLines = 3,
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "by ${spot.reporterName ?: "Anonymous"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (spot.reporterName == "GCC") GccGreen else TextSubtle,
                )
                Text(
                    "${spot.affectedRadiusMeters.toInt()}m radius",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSubtle,
                )
                if (spot.mediaUrls.isNotEmpty()) {
                    Text(
                        "${spot.mediaUrls.size} photo${if (spot.mediaUrls.size != 1) "s" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = GccGreen,
                    )
                }
            }

            // Action buttons (only for pending/verified sightings)
            if (spot.status == com.gcc.shared.model.CommunityReportStatus.PENDING ||
                spot.status == com.gcc.shared.model.CommunityReportStatus.VERIFIED
            ) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Verify button — confirm mess is still there or already clean
                    OutlinedButton(
                        onClick = onVerifySpot,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = GccGreen),
                    ) {
                        Text(
                            "Verify",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        )
                    }
                    // Volunteer to clean
                    Button(
                        onClick = onVolunteerCleanup,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GccGreen),
                    ) {
                        Text(
                            "Clean Up",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        )
                    }
                }
                if (spot.needsVerification) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "This spot needs re-verification",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFF9800),
                    )
                }
            }
        }
    }
}
