package com.gcc.admin

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.arkivanov.decompose.extensions.compose.stack.animation.fade
import com.arkivanov.decompose.extensions.compose.stack.animation.stackAnimation
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.gcc.admin.navigation.AdminRootComponent
import com.gcc.admin.navigation.AdminScreen
import com.gcc.admin.ui.layout.AdminScaffold
import com.gcc.admin.ui.screens.*
import com.gcc.admin.ui.theme.AdminTheme

@Composable
fun AdminApp(root: AdminRootComponent) {
    AdminTheme {
        val childStack by root.childStack.subscribeAsState()
        val currentScreen = childStack.active.configuration

        // Login screen is full-bleed (no sidebar/topbar)
        if (currentScreen is AdminScreen.Login) {
            Children(
                stack = root.childStack,
                animation = stackAnimation(fade()),
            ) { child ->
                AdminLoginScreen(
                    onLoginSuccess = root::onLoginSuccess,
                )
            }
        } else {
            AdminScaffold(
                currentScreen = currentScreen,
                onNavigate = root::navigateTo,
            ) {
                Children(
                    stack = root.childStack,
                    animation = stackAnimation(fade()),
                ) { child ->
                    when (val screen = child.configuration) {
                        is AdminScreen.Login -> AdminLoginScreen(onLoginSuccess = root::onLoginSuccess)
                        is AdminScreen.Dashboard -> AdminDashboardScreen()
                        is AdminScreen.WorkerList -> AdminWorkerListScreen(onWorkerClick = { root.push(AdminScreen.WorkerDetail(it)) })
                        is AdminScreen.WorkerDetail -> AdminWorkerDetailScreen(workerId = screen.workerId, onBack = root::navigateBack)
                        is AdminScreen.SponsorList -> AdminSponsorListScreen(onSponsorClick = { root.push(AdminScreen.SponsorDetail(it)) })
                        is AdminScreen.SponsorDetail -> AdminSponsorDetailScreen(sponsorId = screen.sponsorId, onBack = root::navigateBack)
                        is AdminScreen.JobList -> AdminJobListScreen(onJobClick = { root.push(AdminScreen.JobDetail(it)) })
                        is AdminScreen.JobDetail -> AdminJobDetailScreen(jobId = screen.jobId, onBack = root::navigateBack)
                        is AdminScreen.RouteList -> AdminRouteListScreen(
                            onRouteClick = { root.push(AdminScreen.RouteEditor(it)) },
                            onNewRoute = { root.push(AdminScreen.RouteEditor(null)) },
                        )
                        is AdminScreen.RouteEditor -> AdminRouteEditorScreen(routeId = screen.routeId, onBack = root::navigateBack)
                        is AdminScreen.BlockList -> AdminBlockListScreen()
                        is AdminScreen.BountyList -> AdminBountyListScreen(onBountyClick = { root.push(AdminScreen.BountyDetail(it)) })
                        is AdminScreen.BountyDetail -> AdminBountyDetailScreen(bountyId = screen.bountyId, onBack = root::navigateBack)
                        is AdminScreen.ServiceAreaMap -> AdminServiceAreaMapScreen()
                        is AdminScreen.DisputeList -> AdminDisputeListScreen(onDisputeClick = { root.push(AdminScreen.DisputeDetail(it)) })
                        is AdminScreen.DisputeDetail -> AdminDisputeDetailScreen(disputeId = screen.disputeId, onBack = root::navigateBack)
                        is AdminScreen.Analytics -> AdminAnalyticsScreen()
                        is AdminScreen.FeedbackList -> AdminFeedbackListScreen()
                        is AdminScreen.AddressChanges -> AdminAddressChangesScreen()
                        is AdminScreen.Settings -> AdminSettingsScreen()
                        is AdminScreen.LiveTracking -> AdminLiveTrackingScreen()
                    }
                }
            }
        }
    }
}
