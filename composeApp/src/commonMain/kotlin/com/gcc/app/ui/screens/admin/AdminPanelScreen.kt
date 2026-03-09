package com.gcc.app.ui.screens.admin

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.theme.*
import com.gcc.app.viewmodel.AdminPanelViewModel
import com.gcc.app.viewmodel.AdminTab
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminPanelScreen(
    onBack: () -> Unit = {},
    onNavigateToSpotCreate: (lat: Double, lng: Double) -> Unit = { _, _ -> },
    onNavigateToSpotDetail: (spotId: Int) -> Unit = {},
    onNavigateToRouteDetail: (routeId: Int) -> Unit = {},
) {
    val viewModel = koinInject<AdminPanelViewModel>()
    val uiState by viewModel.uiState.collectAsState()

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
            ScrollableTabRow(
                selectedTabIndex = uiState.selectedTab.ordinal,
                containerColor = GccGoldDark.copy(alpha = 0.1f),
                contentColor = GccGoldDark,
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

            when {
                uiState.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = GccGold)
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
                    AdminTab.OVERVIEW -> AdminOverviewTab(uiState.stats)
                    AdminTab.WORKERS -> AdminWorkersTab(uiState.workers)
                    AdminTab.ROUTES -> AdminRoutesTab(
                        routes = uiState.routes,
                        onRouteClick = onNavigateToRouteDetail,
                        onStatusToggle = { routeId, newStatus -> viewModel.updateRouteStatus(routeId, newStatus) },
                    )
                    AdminTab.BOUNTIES -> AdminBountiesTab(uiState.bounties)
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
