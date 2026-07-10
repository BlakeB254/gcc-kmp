package com.gcc.app.ui.screens.admin

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
    onLogout: () -> Unit = {},
    onNavigateToJobList: (title: String, status: String?) -> Unit = { _, _ -> },
    onNavigateToSpotCreate: (lat: Double, lng: Double) -> Unit = { _, _ -> },
    onNavigateToSpotDetail: (spotId: Int) -> Unit = {},
    onNavigateToRouteDetail: (routeId: Int) -> Unit = {},
    onNavigateToWorkerDetail: (workerId: Int) -> Unit = {},
    onNavigateToBountyDetail: (bountyId: Int) -> Unit = {},
    onNavigateToDashboard: () -> Unit = {},
    onNavigateToMap: () -> Unit = {},
    onNavigateToProfile: () -> Unit = {},
) {
    val viewModel = koinInject<AdminPanelViewModel>()
    val uiState by viewModel.uiState.collectAsState()
    val tokenManager = koinInject<TokenManager>()

    LaunchedEffect(uiState.selectedTab) {
        if (uiState.selectedTab == AdminTab.ROUTES) {
            viewModel.ensureWorkersLoaded()
            viewModel.ensureCurrentWorkerLoaded()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Admin Panel") },
                navigationIcon = {
                    // Admin is only ever entered through switchToTab(), which calls
                    // navigation.replaceAll() — the back stack is always empty here, so
                    // a pop() would be a no-op. The arrow has to name its destination.
                    IconButton(onClick = onNavigateToDashboard) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to dashboard",
                        )
                    }
                },
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
                    navigationIconContentColor = Color.White,
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
                            onNavigateToJobList = onNavigateToJobList,
                        )
                        AdminTab.WORKERS -> AdminWorkersTab(
                            workers = uiState.workers,
                            onWorkerClick = onNavigateToWorkerDetail,
                            onBulkAction = viewModel::bulkWorkerAction,
                        )
                        AdminTab.ROUTES -> AdminRoutesTab(
                            routes = uiState.routes,
                            workers = uiState.workers,
                            currentWorker = uiState.currentWorker,
                            onRouteClick = onNavigateToRouteDetail,
                            onStatusToggle = { routeId, newStatus -> viewModel.updateRouteStatus(routeId, newStatus) },
                            onCreateRoute = viewModel::createRoute,
                            onAssignRoute = viewModel::assignRouteToWorker,
                        )
                        AdminTab.BOUNTIES -> AdminBountiesTab(
                            bounties = uiState.bounties,
                            onBountyClick = onNavigateToBountyDetail,
                        )
                        AdminTab.SPOTS -> AdminSpotsTab(
                            spotReports = uiState.spotReports,
                            error = uiState.error,
                            onSpotClick = onNavigateToSpotDetail,
                            onCreateClick = { onNavigateToSpotCreate(0.0, 0.0) },
                            onDismissError = { viewModel.clearError() },
                        )
                        AdminTab.PAYOUTS -> AdminPayoutsTab(
                            reviews = uiState.pendingReviews,
                            pendingChecks = uiState.pendingChecks,
                            recentPayouts = uiState.recentPayouts,
                            payoutActionError = uiState.payoutActionError,
                            onApprove = { jobId, onResult -> viewModel.approveJob(jobId, onResult) },
                            onDeny = { jobId, reason, onResult -> viewModel.denyJob(jobId, reason, onResult) },
                            onMarkMailed = { payoutId, onResult -> viewModel.markPayoutMailed(payoutId, onResult) },
                            onDismissActionError = { viewModel.clearPayoutActionError() },
                        )
                        AdminTab.BLOCK_PROPOSALS -> AdminBlockProposalsTab(
                            proposals = uiState.blockProposals,
                            actionError = uiState.blockProposalActionError,
                            onApprove = { proposalId, onResult -> viewModel.approveBlockProposal(proposalId, onResult) },
                            onReject = { proposalId, reason, onResult -> viewModel.rejectBlockProposal(proposalId, reason, onResult) },
                            onDismissActionError = { viewModel.clearBlockProposalActionError() },
                        )
                    }
                }
            }
        }
    }
}
