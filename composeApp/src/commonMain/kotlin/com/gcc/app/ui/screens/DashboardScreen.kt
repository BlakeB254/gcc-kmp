package com.gcc.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.components.*
import com.gcc.app.ui.map.ChicagoOverlay
import com.gcc.app.ui.map.GccMap
import com.gcc.app.ui.map.MapStyles
import com.gcc.app.ui.map.RouteOverlay
import com.gcc.app.ui.theme.*
import com.gcc.app.viewmodel.DashboardViewModel
import com.gcc.shared.model.Route
import com.gcc.app.util.padTwo
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.koinInject

@Composable
fun DashboardScreen(
    onRouteClick: (Int) -> Unit = {},
    onProfileClick: () -> Unit = {},
    onMapClick: () -> Unit = {},
    onSpotClick: () -> Unit = {},
    onBountyListClick: () -> Unit = {},
    onWalletClick: () -> Unit = {},
    onLeaderboardClick: () -> Unit = {},
) {
    val viewModel = koinInject<DashboardViewModel>()
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadDashboard()
        viewModel.loadWorkerProfile()
    }

    @OptIn(kotlin.time.ExperimentalTime::class)
    val greeting = remember {
        val now = kotlin.time.Clock.System.now()
        val hour = now.toLocalDateTime(TimeZone.of("America/Chicago")).hour
        when (hour) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..20 -> "Good evening"
            else -> "Good night"
        }
    }
    val workerName = uiState.worker?.name?.split(" ")?.firstOrNull() ?: "Worker"

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
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            // Custom header row
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
                    }
                    GccLogo(
                        size = 32.dp,
                        modifier = Modifier.clickable(onClick = onProfileClick),
                    )
                }
            }

            // Map section
            item {
                Box(
                    modifier = Modifier
                        .height(233.dp)
                        .padding(horizontal = 13.dp)
                        .clickable(onClick = onMapClick),
                ) {
                    GccMap(
                        modifier = Modifier.fillMaxSize(),
                        centerLat = MapStyles.CHICAGO_CENTER_LAT,
                        centerLng = MapStyles.CHICAGO_CENTER_LNG,
                        zoom = MapStyles.ZOOM_NEIGHBORHOOD,
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
                }
            }

            // Stats row
            item {
                val totalEarnings = uiState.worker?.stats?.totalEarnings ?: 0
                val dollars = totalEarnings / 100
                val cents = totalEarnings % 100
                val jobsCompleted = uiState.worker?.stats?.jobsCompleted ?: 0

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
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

            // Quick actions row
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    GccCard(
                        accentColor = CardAccentGold,
                        modifier = Modifier.weight(1f),
                        onClick = onBountyListClick,
                    ) {
                        Text(
                            "Bounties",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            "Browse open bounties",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                        )
                    }
                    GccCard(
                        accentColor = CardAccentBlue,
                        modifier = Modifier.weight(1f),
                        onClick = onWalletClick,
                    ) {
                        Text(
                            "Wallet",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            "Manage funds",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                        )
                    }
                }
            }

            // Second action row
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    GccCard(
                        accentColor = CardAccentPurple,
                        modifier = Modifier.weight(1f),
                        onClick = onLeaderboardClick,
                    ) {
                        Text(
                            "Leaderboard",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            "See top cleaners",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                        )
                    }
                    // Placeholder spacer for second column symmetry
                    Spacer(modifier = Modifier.weight(1f))
                }
            }

            // Available Routes header
            item {
                SectionHeader(
                    title = "Available Routes",
                    modifier = Modifier.padding(horizontal = 13.dp),
                )
            }

            // Route list or loading/empty state
            if (uiState.isLoading) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = GccGreen)
                    }
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

            // Error message
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
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
