package com.gcc.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.gcc.app.ui.components.GccBottomNavBar
import com.gcc.app.ui.components.NetworkImage
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
    onAdminClick: () -> Unit = {},
    onProfileClick: () -> Unit = {},
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

    // Collect image location stars from all routes
    val imageStars: List<Coordinates> = remember(uiState.routes) {
        uiState.routes.mapNotNull { route ->
            val lat = route.imageLocationLat
            val lng = route.imageLocationLng
            if (lat != null && lng != null) Coordinates(lng = lng, lat = lat) else null
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

    // Check if user is outside their service area
    val isExploring = remember(currentLocation, serviceArea) {
        val loc = currentLocation ?: return@remember false
        val sa = serviceArea ?: return@remember false
        loc.lat !in sa.bounds.south..sa.bounds.north ||
        loc.lng !in sa.bounds.west..sa.bounds.east
    }

    // Worker info for the panel
    val areaName = uiState.serviceAreaName
        ?: serviceArea?.displayName?.replace("\n", " ")
        ?: "North Lawndale"
    val totalRoutes = uiState.routes.size
    val jobsCompleted = uiState.worker?.stats?.jobsCompleted ?: 0
    val totalEarnings = uiState.worker?.stats?.totalEarnings ?: 0

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            GccBottomNavBar(
                selectedTab = 1,
                isAdmin = isAdmin,
                onTabSelected = { tab ->
                    when (tab) {
                        0 -> onBack()
                        1 -> { /* already on map */ }
                        2 -> if (isAdmin) onAdminClick() else onProfileClick()
                    }
                },
            )
        },
    ) { padding ->
    Box(modifier = Modifier.fillMaxSize().padding(padding)) {
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
            imageStars = if (mapMode == MapMode.MY_AREA) imageStars else emptyList(),
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
            // DEBUG: always show route data state
            androidx.compose.material3.Surface(
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 50.dp, end = 8.dp),
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF333333),
            ) {
                val r0 = uiState.routes.firstOrNull()
                Text(
                    "R:${uiState.routes.size} lat:${r0?.imageLocationLat ?: "null"} img:${if (r0?.imageUrl != null) "yes" else "no"} desc:${r0?.description ?: "null"}",
                    modifier = Modifier.padding(6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
            }

            // Top bar: toggle + community info inline
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Community info panel (left) — only in My Area
                if (mapMode == MapMode.MY_AREA && selectedSpot == null) {
                    CommunityInfoPanel(
                        communityAreaName = areaName,
                        totalRoutes = totalRoutes,
                        registeredRoutes = 0,
                        jobsCompleted = jobsCompleted,
                        totalEarnings = totalEarnings,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }

                // Mode toggle (right)
                MapModeToggle(
                    currentMode = mapMode,
                    onModeChange = {
                        mapMode = it
                        overrideCenterLat = null
                        overrideCenterLng = null
                        overrideZoom = null
                        selectedSpot = null
                    },
                )
            }

            // Spot create FAB (top-right)
            // (close button removed — bottom nav bar provides navigation)

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

    // Full-screen image viewer state
    var fullScreenImageIndex by remember { mutableStateOf<Int?>(null) }

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

            Spacer(modifier = Modifier.height(10.dp))

            // Photos — larger thumbnails, tappable for full-screen view
            if (spot.mediaUrls.isNotEmpty()) {
                PhotoStrip(
                    urls = spot.mediaUrls,
                    photoSize = 120,
                    onPhotoClick = { index -> fullScreenImageIndex = index },
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            spot.description?.let { desc ->
                Text(
                    desc,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted,
                    maxLines = 4,
                )
                Spacer(modifier = Modifier.height(6.dp))
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
                        "${spot.mediaUrls.size} photo${if (spot.mediaUrls.size != 1) "s" else ""} \u2022 tap to enlarge",
                        style = MaterialTheme.typography.bodySmall,
                        color = GccGreen,
                    )
                }
            }

            // Action buttons (only for pending/verified sightings)
            if (spot.status == com.gcc.shared.model.CommunityReportStatus.PENDING ||
                spot.status == com.gcc.shared.model.CommunityReportStatus.VERIFIED
            ) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
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

    // Full-screen image gallery overlay
    fullScreenImageIndex?.let { startIndex ->
        FullScreenImageGallery(
            urls = spot.mediaUrls,
            captions = spot.mediaCaptions,
            startIndex = startIndex,
            onDismiss = { fullScreenImageIndex = null },
        )
    }
}

@Composable
private fun FullScreenImageGallery(
    urls: List<String>,
    captions: List<String> = emptyList(),
    startIndex: Int,
    onDismiss: () -> Unit,
) {
    var currentIndex by remember { mutableStateOf(startIndex) }
    val currentCaption = captions.getOrNull(currentIndex)?.takeIf { it.isNotBlank() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.95f))
            .clickable(onClick = onDismiss)
            .pointerInput(urls.size) {
                // Swipe left/right to navigate
                var totalDrag = 0f
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (totalDrag < -80f && currentIndex < urls.size - 1) {
                            currentIndex++
                        } else if (totalDrag > 80f && currentIndex > 0) {
                            currentIndex--
                        }
                        totalDrag = 0f
                    },
                    onDragCancel = { totalDrag = 0f },
                    onHorizontalDrag = { _, dragAmount -> totalDrag += dragAmount },
                )
            },
    ) {
        // Close button
        TextButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("\u2715", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        }

        // Current image — large
        NetworkImage(
            url = urls[currentIndex],
            contentDescription = "Photo ${currentIndex + 1} of ${urls.size}",
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.65f)
                .align(Alignment.Center)
                .padding(16.dp),
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
        )

        // Navigation, caption, and counter at bottom
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp, start = 24.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Caption
            currentCaption?.let { caption ->
                Text(
                    caption,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.9f),
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Page indicator
            Text(
                "${currentIndex + 1} / ${urls.size}",
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
            )

            if (urls.size > 1) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Swipe to navigate",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.5f),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    TextButton(
                        onClick = { if (currentIndex > 0) currentIndex-- },
                        enabled = currentIndex > 0,
                    ) {
                        Text(
                            "\u2190 Prev",
                            color = if (currentIndex > 0) Color.White else Color.White.copy(alpha = 0.3f),
                        )
                    }
                    TextButton(
                        onClick = { if (currentIndex < urls.size - 1) currentIndex++ },
                        enabled = currentIndex < urls.size - 1,
                    ) {
                        Text(
                            "Next \u2192",
                            color = if (currentIndex < urls.size - 1) Color.White else Color.White.copy(alpha = 0.3f),
                        )
                    }
                }
            }
        }
    }
}
