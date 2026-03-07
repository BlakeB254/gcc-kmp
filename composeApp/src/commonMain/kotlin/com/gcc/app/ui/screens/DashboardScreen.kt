package com.gcc.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gcc.app.ui.components.*
import com.gcc.app.ui.map.GccMap
import com.gcc.app.ui.map.MapStyles
import com.gcc.app.ui.map.RouteThumbnail
import com.gcc.app.ui.map.chicagoCommunityAreas
import com.gcc.app.ui.theme.*
import com.gcc.app.viewmodel.DashboardViewModel
import com.gcc.shared.model.Coordinates
import com.gcc.shared.model.Route
import com.gcc.app.util.padTwo
import kotlin.math.ln
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.tan
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onRouteClick: (Int) -> Unit = {},
    onProfileClick: () -> Unit = {},
    onMapClick: () -> Unit = {},
    onSpotClick: () -> Unit = {},
    onBountyListClick: () -> Unit = {},
    onWalletClick: () -> Unit = {},
    onLeaderboardClick: () -> Unit = {},
    onFeedClick: () -> Unit = {},
    onAchievementsClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
) {
    val viewModel = koinInject<DashboardViewModel>()
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadDashboard()
        viewModel.loadWorkerProfile()
        viewModel.loadServiceArea()
    }

    val greeting = remember {
        val now = kotlinx.datetime.Clock.System.now()
        val hour = now.toLocalDateTime(TimeZone.of("America/Chicago")).hour
        when (hour) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..20 -> "Good evening"
            else -> "Good night"
        }
    }
    val workerName = uiState.worker?.name?.split(" ")?.firstOrNull() ?: "Worker"

    // Extract route coordinates for the map preview
    val routeCoordinates: List<List<Coordinates>> = remember(uiState.routes) {
        uiState.routes.map { route ->
            route.geometry?.coordinates?.map { point ->
                Coordinates(lng = point[0], lat = point[1])
            } ?: route.checkpoints
                .sortedBy { it.sequenceOrder }
                .map { it.coordinates }
        }
    }

    // Compute map center and zoom to fit all route coordinates
    val serviceArea = remember { chicagoCommunityAreas.find { it.isServiceArea } }
    val allPoints = remember(routeCoordinates) { routeCoordinates.flatten() }
    val mapCenter = remember(allPoints) {
        if (allPoints.isNotEmpty()) {
            val minLat = allPoints.minOf { it.lat }
            val maxLat = allPoints.maxOf { it.lat }
            val minLng = allPoints.minOf { it.lng }
            val maxLng = allPoints.maxOf { it.lng }
            Pair((minLat + maxLat) / 2.0, (minLng + maxLng) / 2.0)
        } else {
            // Fallback to service area center
            val sa = serviceArea
            if (sa != null) Pair(
                (sa.bounds.north + sa.bounds.south) / 2.0,
                (sa.bounds.east + sa.bounds.west) / 2.0,
            ) else Pair(MapStyles.CHICAGO_CENTER_LAT, MapStyles.CHICAGO_CENTER_LNG)
        }
    }
    val mapZoom = remember(allPoints) {
        if (allPoints.size >= 2) {
            val minLat = allPoints.minOf { it.lat }
            val maxLat = allPoints.maxOf { it.lat }
            val minLng = allPoints.minOf { it.lng }
            val maxLng = allPoints.maxOf { it.lng }
            // Estimate zoom to fit the bounding box with padding
            val latSpan = (maxLat - minLat) * 1.3 // 30% padding
            val lngSpan = (maxLng - minLng) * 1.3
            val latZoom = if (latSpan > 0) ln(360.0 / latSpan) / ln(2.0) else 16.0
            val lngZoom = if (lngSpan > 0) ln(360.0 / lngSpan) / ln(2.0) else 16.0
            minOf(latZoom, lngZoom).coerceIn(10.0, 17.0)
        } else {
            MapStyles.ZOOM_SERVICE_AREA
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        floatingActionButton = {
            FloatingActionButton(
                onClick = onSpotClick,
                containerColor = GccGreen,
            ) {
                Text("\uD83D\uDCF8", style = MaterialTheme.typography.headlineMedium)
            }
        },
        bottomBar = {
            GccBottomNavBar(
                selectedTab = 0,
                onTabSelected = { tab ->
                    when (tab) {
                        1 -> onMapClick()
                        2 -> onProfileClick()
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            // --- Header with greeting ---
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 13.dp, vertical = 13.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            "$greeting,",
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextMuted,
                        )
                        Text(
                            workerName,
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        )
                        uiState.serviceAreaName?.let { area ->
                            Text(area, style = MaterialTheme.typography.bodySmall, color = TextMuted)
                        }
                    }
                    GccLogo(
                        size = 32.dp,
                        modifier = Modifier.clickable(onClick = onProfileClick),
                    )
                }
            }

            // --- Map preview (clean — no overlays, no controls) ---
            item {
                Box(
                    modifier = Modifier
                        .height(233.dp)
                        .padding(horizontal = 13.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .clickable(onClick = onMapClick),
                ) {
                    GccMap(
                        modifier = Modifier.fillMaxSize(),
                        centerLat = mapCenter.first,
                        centerLng = mapCenter.second,
                        zoom = mapZoom,
                        routeCoordinates = routeCoordinates,
                        showControls = false,
                    )

                    // Expand affordance — small icon in bottom-right corner
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .size(28.dp),
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.85f),
                        shadowElevation = 2.dp,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                "\u2922", // ⤢ expand arrows
                                fontSize = 14.sp,
                                color = GccGreenDark,
                            )
                        }
                    }
                }
            }

            // --- Stats row ---
            item {
                val totalEarnings = uiState.worker?.stats?.totalEarnings ?: 0
                val dollars = totalEarnings / 100
                val cents = totalEarnings % 100
                val jobsCompleted = uiState.worker?.stats?.jobsCompleted ?: 0

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    StatCard(
                        value = "$$dollars.${cents.padTwo()}",
                        label = "Earned",
                        accentColor = CardAccentGreen,
                        modifier = Modifier.weight(1f),
                    )
                    StatCard(
                        value = "$jobsCompleted",
                        label = "Jobs",
                        accentColor = CardAccentBlue,
                        modifier = Modifier.weight(1f),
                    )
                    StatCard(
                        value = "0",
                        label = "Streak",
                        accentColor = CardAccentGold,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // --- Quick Actions (circle icon grid) ---
            item {
                SectionHeader(
                    title = "Quick Actions",
                    modifier = Modifier.padding(horizontal = 13.dp),
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 13.dp),
                    horizontalArrangement = Arrangement.spacedBy(13.dp),
                ) {
                    QuickActionButton(
                        emoji = "\uD83D\uDCF8",
                        label = "Spot\nReport",
                        bgColor = CardAccentRed,
                        onClick = onSpotClick,
                    )
                    QuickActionButton(
                        emoji = "\uD83C\uDFC6",
                        label = "Bounties",
                        bgColor = CardAccentGold,
                        onClick = onBountyListClick,
                    )
                    QuickActionButton(
                        emoji = "\uD83D\uDCB0",
                        label = "Wallet",
                        bgColor = CardAccentBlue,
                        onClick = onWalletClick,
                    )
                    QuickActionButton(
                        emoji = "\uD83C\uDFC5",
                        label = "Leader-\nboard",
                        bgColor = CardAccentPurple,
                        onClick = onLeaderboardClick,
                    )
                    QuickActionButton(
                        emoji = "\uD83D\uDCF0",
                        label = "Feed",
                        bgColor = GccGreen,
                        onClick = onFeedClick,
                    )
                    QuickActionButton(
                        emoji = "\u2B50",
                        label = "Awards",
                        bgColor = GccGold,
                        onClick = onAchievementsClick,
                    )
                    QuickActionButton(
                        emoji = "\uD83D\uDDFA\uFE0F",
                        label = "Map",
                        bgColor = GccGreenDark,
                        onClick = onMapClick,
                    )
                    QuickActionButton(
                        emoji = "\u2699\uFE0F",
                        label = "Settings",
                        bgColor = TextMuted,
                        onClick = onSettingsClick,
                    )
                }
            }

            // --- Available Routes header ---
            item {
                SectionHeader(
                    title = "Available Routes",
                    modifier = Modifier.padding(horizontal = 13.dp),
                )
            }

            // --- Route list ---
            if (uiState.isLoading) {
                item {
                    SkeletonList(count = 3)
                }
            } else if (uiState.routes.isEmpty()) {
                item {
                    Text(
                        "No routes available yet. Check back soon!",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted,
                        modifier = Modifier.padding(horizontal = 13.dp),
                    )
                }
            } else {
                items(uiState.routes, key = { it.id }) { route ->
                    RouteListItem(
                        route = route,
                        onClick = { onRouteClick(route.id) },
                        modifier = Modifier.padding(horizontal = 13.dp),
                    )
                }
            }

            // --- Error banner ---
            uiState.error?.let { error ->
                item {
                    GccCard(
                        accentColor = CardAccentRed,
                        modifier = Modifier.padding(horizontal = 13.dp),
                    ) {
                        Text(
                            error,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }
        }
        }
    }
}

// --- Quick Action circle button ---

@Composable
private fun QuickActionButton(
    emoji: String,
    label: String,
    bgColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(64.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(55.dp)
                .shadow(elevation = 2.dp, shape = CircleShape)
                .clip(CircleShape)
                .background(bgColor.copy(alpha = 0.12f))
                .background(Color.White.copy(alpha = 0.7f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(55.dp)
                    .clip(CircleShape)
                    .background(bgColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    emoji,
                    fontSize = 24.sp,
                )
            }
        }
        Spacer(modifier = Modifier.height(5.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 12.sp,
        )
    }
}

// --- Route list item with thumbnail ---

@Composable
private fun RouteListItem(
    route: Route,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GccCard(
        modifier = modifier,
        accentColor = CardAccentGreen,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Route visual thumbnail
            RouteThumbnail(
                route = route,
                modifier = Modifier.size(55.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    route.displayName,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusChip(
                        text = "Available",
                        dotColor = GccAvailable,
                        backgroundColor = ChipBgAvailable,
                    )
                    Text(
                        "${route.checkpoints.size} checkpoints",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                }
                route.estimatedDurationMinutes?.let { mins ->
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        "~$mins min",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSubtle,
                    )
                }
            }
            route.payoutAmount?.let { payout ->
                Text(
                    "$${payout / 100}.${(payout % 100).padTwo()}",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = GccGreen,
                )
            }
        }
    }
}
