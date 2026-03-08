package com.gcc.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gcc.app.platform.LocationProvider
import com.gcc.app.ui.theme.GccGold
import com.gcc.app.ui.theme.GccGoldDark
import com.gcc.app.ui.theme.GccGreen
import com.gcc.shared.api.GccApiClient
import com.gcc.shared.model.*
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

// ── Reusable admin components ────────────────────────────────────────

@Composable
private fun StatCard(label: String, value: String, icon: String, color: Color = GccGold) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(icon, fontSize = 28.sp)
            Column(modifier = Modifier.weight(1f)) {
                Text(value, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = color)
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        color = GccGoldDark,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun AdminListItem(
    title: String,
    subtitle: String,
    trailing: String? = null,
    trailingColor: Color = GccGold,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (trailing != null) {
                Text(trailing, style = MaterialTheme.typography.labelLarge, color = trailingColor, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ── Admin Panel Tabs ─────────────────────────────────────────────────

private enum class AdminTab(val label: String, val icon: String) {
    OVERVIEW("Overview", "\uD83D\uDCCA"),
    WORKERS("Workers", "\uD83D\uDC77"),
    ROUTES("Routes", "\uD83D\uDDFA"),
    BOUNTIES("Bounties", "\uD83C\uDFAF"),
    SPOTS("Spots", "\uD83D\uDCCD"),
}

// ── Main Screen ──────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminPanelScreen(
    onBack: () -> Unit = {},
) {
    val apiClient = koinInject<GccApiClient>()
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableStateOf(AdminTab.OVERVIEW) }
    var stats by remember { mutableStateOf<AdminStatsResponse?>(null) }
    var workers by remember { mutableStateOf<List<Worker>>(emptyList()) }
    var routes by remember { mutableStateOf<List<Route>>(emptyList()) }
    var bounties by remember { mutableStateOf<List<Bounty>>(emptyList()) }
    var spotReports by remember { mutableStateOf<List<CommunityReport>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(selectedTab) {
        isLoading = true
        error = null
        try {
            when (selectedTab) {
                AdminTab.OVERVIEW -> stats = apiClient.getAdminStats()
                AdminTab.WORKERS -> workers = apiClient.getAdminWorkers()
                AdminTab.ROUTES -> routes = apiClient.getAdminRoutes()
                AdminTab.BOUNTIES -> bounties = apiClient.getAdminBounties()
                AdminTab.SPOTS -> spotReports = apiClient.getAdminSpotReports()
            }
        } catch (e: Exception) {
            error = when {
                e.message?.contains("401") == true || e.message?.contains("403") == true ->
                    "Not authorized. Try signing out and back in."
                e.message?.contains("404") == true ->
                    "Endpoint not found. The server may need updating."
                e.message?.contains("500") == true ->
                    "Server error. Please try again later."
                else -> e.message ?: "Failed to load data"
            }
        }
        isLoading = false
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Admin Panel") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("\u2190 Back", color = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = GccGoldDark,
                    titleContentColor = Color.White,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Tab row
            ScrollableTabRow(
                selectedTabIndex = selectedTab.ordinal,
                containerColor = GccGoldDark.copy(alpha = 0.1f),
                contentColor = GccGoldDark,
                edgePadding = 8.dp,
            ) {
                AdminTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        text = { Text("${tab.icon} ${tab.label}") },
                    )
                }
            }

            when {
                isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = GccGold)
                }
                error != null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(error!!, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = { selectedTab = selectedTab }) { Text("Retry") }
                    }
                }
                else -> when (selectedTab) {
                    AdminTab.OVERVIEW -> OverviewTab(stats)
                    AdminTab.WORKERS -> WorkersTab(workers, apiClient)
                    AdminTab.ROUTES -> RoutesTab(routes, apiClient) { routes = it }
                    AdminTab.BOUNTIES -> BountiesTab(bounties, apiClient)
                    AdminTab.SPOTS -> SpotsTab(spotReports, apiClient) { spotReports = it }
                }
            }
        }
    }
}

// ── Overview Tab ─────────────────────────────────────────────────────

@Composable
private fun OverviewTab(stats: AdminStatsResponse?) {
    if (stats == null) return
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { SectionHeader("Platform") }
        item { StatCard("Total Workers", stats.totalWorkers.toString(), "\uD83D\uDC77") }
        item { StatCard("Verified Workers", stats.verifiedWorkers.toString(), "\u2705", GccGreen) }
        item { StatCard("Pending Verifications", stats.pendingVerifications.toString(), "\u23F3", Color(0xFFFF9800)) }
        item { SectionHeader("Operations") }
        item { StatCard("Active Jobs", stats.activeJobs.toString(), "\u26A1", GccGreen) }
        item { StatCard("Completed Jobs", stats.completedJobs.toString(), "\u2705") }
        item { StatCard("Total Routes", stats.totalRoutes.toString(), "\uD83D\uDDFA") }
        item { SectionHeader("Bounties") }
        item { StatCard("Active Bounties", stats.activeBounties.toString(), "\uD83C\uDFAF", GccGreen) }
        item { StatCard("Total Bounties", stats.totalBounties.toString(), "\uD83D\uDCB0") }
        item { SectionHeader("Issues") }
        item { StatCard("Open Disputes", stats.openDisputes.toString(), "\u26A0\uFE0F", if (stats.openDisputes > 0) Color(0xFFFF5252) else GccGold) }
    }
}

// ── Workers Tab ──────────────────────────────────────────────────────

@Composable
private fun WorkersTab(workers: List<Worker>, apiClient: GccApiClient) {
    val scope = rememberCoroutineScope()
    var workerList by remember(workers) { mutableStateOf(workers) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Text(
                "${workerList.size} workers",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        items(workerList, key = { it.id }) { worker ->
            val verified = worker.verification.status == VerificationStatus.APPROVED
            AdminListItem(
                title = worker.name,
                subtitle = "${worker.email} \u2022 ${if (verified) "Verified" else worker.verification.status.name.lowercase().replaceFirstChar { it.uppercase() }}",
                trailing = worker.role,
                trailingColor = if (worker.role == "admin") GccGold else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── Routes Tab ───────────────────────────────────────────────────────

@Composable
private fun RoutesTab(
    routes: List<Route>,
    apiClient: GccApiClient,
    onRoutesUpdated: (List<Route>) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var routeList by remember(routes) { mutableStateOf(routes) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Text(
                "${routeList.size} routes",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        items(routeList, key = { it.id }) { route ->
            val distMi = route.distanceMeters?.let { m -> val mi = (m / 1609.34 * 10).toInt() / 10.0; "${mi} mi" } ?: "N/A"
            val statusColor = when (route.status) {
                RouteStatus.PUBLISHED -> GccGreen
                RouteStatus.DRAFT -> Color.Gray
                RouteStatus.ARCHIVED -> Color(0xFFFFC107)
            }
            val nextStatus = when (route.status) {
                RouteStatus.PUBLISHED -> "draft"
                RouteStatus.DRAFT -> "archived"
                RouteStatus.ARCHIVED -> "published"
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(route.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(
                            "$distMi \u2022 ${route.checkpoints.size} checkpoints",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    route.payoutAmount?.let {
                        Text(
                            "$${it}",
                            style = MaterialTheme.typography.labelLarge,
                            color = GccGreen,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                    Surface(
                        modifier = Modifier.clickable {
                            scope.launch {
                                try {
                                    val updated = apiClient.updateRouteStatus(route.id, nextStatus)
                                    routeList = routeList.map { if (it.id == route.id) updated else it }
                                    onRoutesUpdated(routeList)
                                } catch (_: Exception) { }
                            }
                        },
                        shape = RoundedCornerShape(16.dp),
                        color = statusColor.copy(alpha = 0.15f),
                    ) {
                        Text(
                            route.status.name.lowercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = statusColor,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

// ── Spots Tab ────────────────────────────────────────────────────────

@Composable
private fun SpotsTab(
    spotReports: List<CommunityReport>,
    apiClient: GccApiClient,
    onSpotsUpdated: (List<CommunityReport>) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val locationProvider = koinInject<LocationProvider>()
    val currentLocation by locationProvider.currentLocation.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        locationProvider.startLocationUpdates()
        onDispose { locationProvider.stopLocationUpdates() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                Text(
                    "${spotReports.size} spot reports",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            items(spotReports, key = { it.id }) { report ->
                val reporterDisplay = if (report.reporterName == "GCC") "GCC" else (report.reporterName ?: "Anonymous")
                val descSnippet = report.description?.take(60)?.let {
                    if ((report.description?.length ?: 0) > 60) "$it..." else it
                } ?: "No description"
                val mediaCount = report.mediaUrls.size
                val statusColor = when (report.status) {
                    CommunityReportStatus.PENDING -> Color(0xFFFF9800)
                    CommunityReportStatus.VERIFIED -> GccGreen
                    CommunityReportStatus.RESOLVED -> GccGold
                    CommunityReportStatus.ACKNOWLEDGED -> Color(0xFF2196F3)
                    CommunityReportStatus.REJECTED -> Color(0xFFFF5252)
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    reporterDisplay,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = if (report.reporterName == "GCC") GccGreen else MaterialTheme.colorScheme.onSurface,
                                )
                                if (mediaCount > 0) {
                                    Text(
                                        " \u2022 $mediaCount photo${if (mediaCount != 1) "s" else ""}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Text(
                                "${formatCoord(report.coordinates.lat)}, ${formatCoord(report.coordinates.lng)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                descSnippet,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = statusColor.copy(alpha = 0.15f),
                        ) {
                            Text(
                                report.status.name.lowercase(),
                                style = MaterialTheme.typography.labelMedium,
                                color = statusColor,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }

        // FAB to create admin spot
        FloatingActionButton(
            onClick = { showCreateDialog = true },
            containerColor = GccGreen,
            contentColor = Color.White,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            Text("+", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
    }

    if (showCreateDialog) {
        AdminCreateSpotDialog(
            currentLat = currentLocation?.lat ?: 0.0,
            currentLng = currentLocation?.lng ?: 0.0,
            onDismiss = { showCreateDialog = false },
            onSubmit = { request ->
                scope.launch {
                    try {
                        apiClient.createAdminSpot(request)
                        val updated = apiClient.getAdminSpotReports()
                        onSpotsUpdated(updated)
                    } catch (_: Exception) { }
                    showCreateDialog = false
                }
            },
        )
    }
}

@Composable
private fun AdminCreateSpotDialog(
    currentLat: Double,
    currentLng: Double,
    onDismiss: () -> Unit,
    onSubmit: (CreateSpotRequest) -> Unit,
) {
    var isSighting by remember { mutableStateOf(true) }
    var description by remember { mutableStateOf("") }
    var radiusMeters by remember { mutableStateOf(15f) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Spot Report") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Type toggle
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = isSighting,
                        onClick = { isSighting = true },
                        label = { Text("Sighting") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = GccGreen.copy(alpha = 0.15f),
                            selectedLabelColor = GccGreen,
                        ),
                    )
                    FilterChip(
                        selected = !isSighting,
                        onClick = { isSighting = false },
                        label = { Text("Volunteer Cleanup") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = GccGreen.copy(alpha = 0.15f),
                            selectedLabelColor = GccGreen,
                        ),
                    )
                }

                // GPS coordinates
                Text(
                    "Location: ${formatCoord(currentLat)}, ${formatCoord(currentLng)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Description
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )

                // Radius slider
                Text(
                    "Radius: ${radiusMeters.toInt()}m",
                    style = MaterialTheme.typography.bodySmall,
                )
                Slider(
                    value = radiusMeters,
                    onValueChange = { radiusMeters = it },
                    valueRange = 5f..100f,
                    steps = 18,
                    colors = SliderDefaults.colors(
                        thumbColor = GccGold,
                        activeTrackColor = GccGold,
                    ),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSubmit(
                        CreateSpotRequest(
                            type = if (isSighting) CommunityReportType.SIGHTING else CommunityReportType.VOLUNTEER_CLEANUP,
                            lat = currentLat,
                            lng = currentLng,
                            description = description.ifBlank { null },
                            affectedRadiusMeters = radiusMeters.toDouble(),
                            reporterName = "GCC",
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = GccGreen),
                enabled = currentLat != 0.0 || currentLng != 0.0,
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun formatCoord(value: Double): String {
    val intPart = value.toInt()
    val fracPart = ((kotlin.math.abs(value) - kotlin.math.abs(intPart.toDouble())) * 10000).toInt()
    return "$intPart.${fracPart.toString().padStart(4, '0')}"
}

// ── Bounties Tab ─────────────────────────────────────────────────────

@Composable
private fun BountiesTab(bounties: List<Bounty>, apiClient: GccApiClient) {
    val scope = rememberCoroutineScope()

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Text(
                "${bounties.size} bounties",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        items(bounties, key = { it.id }) { bounty ->
            val statusColor = when (bounty.status) {
                BountyStatus.OPEN -> GccGreen
                BountyStatus.CLAIMED, BountyStatus.FIRST_REFUSAL -> Color(0xFFFF9800)
                BountyStatus.COMPLETED -> GccGold
                BountyStatus.IN_PROGRESS -> Color(0xFF2196F3)
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            AdminListItem(
                title = bounty.title,
                subtitle = "${bounty.status.name.lowercase().replace('_', ' ')} \u2022 ${bounty.communityArea ?: "Unknown area"}",
                trailing = "$${bounty.amount}",
                trailingColor = statusColor,
            )
        }
    }
}
