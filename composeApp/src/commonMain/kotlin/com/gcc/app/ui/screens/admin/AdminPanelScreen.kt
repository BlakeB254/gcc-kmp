package com.gcc.app.ui.screens.admin

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.components.GccBottomNavBar
import com.gcc.app.ui.theme.*
import com.gcc.app.viewmodel.AdminPanelViewModel
import com.gcc.app.viewmodel.AdminTab
import com.gcc.shared.auth.TokenManager
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminPanelScreen(
    onBack: () -> Unit = {},
    onLogout: () -> Unit = {},
    onNavigateToSpotCreate: (lat: Double, lng: Double) -> Unit = { _, _ -> },
    onNavigateToSpotDetail: (spotId: Int) -> Unit = {},
    onNavigateToRouteDetail: (routeId: Int) -> Unit = {},
    onNavigateToWorkerDetail: (workerId: Int) -> Unit = {},
    onNavigateToDashboard: () -> Unit = {},
    onNavigateToMap: () -> Unit = {},
    onNavigateToProfile: () -> Unit = {},
) {
    val viewModel = koinInject<AdminPanelViewModel>()
    val uiState by viewModel.uiState.collectAsState()
    val tokenManager = koinInject<TokenManager>()

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Admin Panel") },
                actions = {
                    TextButton(onClick = {
                        tokenManager.logout()
                        onLogout()
                    }) {
                        Text("Logout", color = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = GccSkyDark,
                    titleContentColor = Color.White,
                ),
            )
        },
        bottomBar = {
            GccBottomNavBar(
                selectedTab = 2,
                isAdmin = true,
                onTabSelected = { tab ->
                    when (tab) {
                        0 -> onNavigateToDashboard()
                        1 -> onNavigateToMap()
                        2 -> { /* already on admin */ }
                        3 -> onNavigateToProfile()
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            ScrollableTabRow(
                selectedTabIndex = uiState.selectedTab.ordinal,
                containerColor = GccSkyDark.copy(alpha = 0.1f),
                contentColor = GccSkyDark,
                edgePadding = 8.dp,
            ) {
                AdminTab.entries.forEach { tab ->
                    Tab(
                        selected = uiState.selectedTab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        text = { Text("${tab.icon} ${tab.label}") },
                    )
                }
            }

            PullToRefreshBox(
                isRefreshing = uiState.isRefreshing,
                onRefresh = { viewModel.forceRefreshCurrentTab() },
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    uiState.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = GccSky)
                    }
                    uiState.error != null && uiState.selectedTab != AdminTab.SPOTS -> Box(
                        Modifier.fillMaxSize().padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(uiState.error!!, color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(onClick = { viewModel.refreshCurrentTab() }) { Text("Retry") }
                        }
                    }
                    else -> when (uiState.selectedTab) {
                        AdminTab.OVERVIEW -> AdminOverviewTab(
                            stats = uiState.stats,
                            recentActivity = uiState.recentActivity,
                            dailyStats = uiState.dailyStats,
                            onNavigateToTab = { tab -> viewModel.selectTab(tab) },
                        )
                        AdminTab.WORKERS -> AdminWorkersTab(
                            workers = uiState.workers,
                            onWorkerClick = onNavigateToWorkerDetail,
                            onBulkAction = viewModel::bulkWorkerAction,
                        )
                        AdminTab.ROUTES -> AdminRoutesTab(
                            routes = uiState.routes,
                            onRouteClick = onNavigateToRouteDetail,
                            onStatusToggle = { routeId, newStatus -> viewModel.updateRouteStatus(routeId, newStatus) },
                            onCreateRoute = viewModel::createRoute,
                        )
                        AdminTab.BOUNTIES -> AdminBountiesTab(
                            bounties = uiState.bounties,
                            onBountyClick = { /* TODO: navigate to bounty detail */ },
                        )
                        AdminTab.SPOTS -> AdminSpotsTab(
                            spotReports = uiState.spotReports,
                            error = uiState.error,
                            onSpotClick = onNavigateToSpotDetail,
                            onCreateClick = { onNavigateToSpotCreate(0.0, 0.0) },
                            onDismissError = { viewModel.clearError() },
                        )
                    }
                }
            }
        }
    }
}
