package com.gcc.admin

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.gcc.admin.navigation.AdminRootComponent
import com.gcc.admin.navigation.AdminScreen
import com.gcc.admin.ui.layout.AdminSidebar
import com.gcc.admin.ui.screens.*
import com.gcc.admin.ui.theme.AdminTheme

@Composable
fun AdminApp(root: AdminRootComponent) {
    AdminTheme {
        val childStack by root.childStack.subscribeAsState()
        val activeScreen = childStack.active.configuration

        // Login gets the full screen (no sidebar)
        if (activeScreen is AdminScreen.Login) {
            Children(stack = root.childStack) { child ->
                AdminLoginScreen(
                    onLoginSuccess = { root.onLoginSuccess() }
                )
            }
            return@AdminTheme
        }

        // All other screens: sidebar + content
        Row(modifier = Modifier.fillMaxSize()) {
            AdminSidebar(
                activeScreen = activeScreen,
                onNavigate = { root.navigateTo(it) },
            )

            Children(
                stack = root.childStack,
                modifier = Modifier.weight(1f),
            ) { child ->
                when (val screen = child.configuration) {
                    is AdminScreen.Login -> { /* handled above */ }
                    is AdminScreen.Dashboard -> AdminDashboardScreen()
                    is AdminScreen.WorkerList -> AdminWorkerListScreen(
                        onWorkerClick = { root.push(AdminScreen.WorkerDetail(it)) }
                    )
                    is AdminScreen.WorkerDetail -> AdminWorkerDetailScreen(
                        workerId = screen.workerId,
                        onBack = { root.pop() }
                    )
                    is AdminScreen.SponsorList -> AdminSponsorListScreen(
                        onSponsorClick = { root.push(AdminScreen.SponsorDetail(it)) }
                    )
                    is AdminScreen.SponsorDetail -> AdminSponsorDetailScreen(
                        sponsorId = screen.sponsorId,
                        onBack = { root.pop() }
                    )
                    is AdminScreen.JobList -> AdminJobListScreen(
                        onJobClick = { root.push(AdminScreen.JobDetail(it)) }
                    )
                    is AdminScreen.JobDetail -> AdminJobDetailScreen(
                        jobId = screen.jobId,
                        onBack = { root.pop() }
                    )
                    is AdminScreen.RouteList -> AdminRouteListScreen(
                        onRouteClick = { root.push(AdminScreen.RouteEditor(it)) },
                        onCreateRoute = { root.push(AdminScreen.RouteEditor(null)) }
                    )
                    is AdminScreen.RouteEditor -> AdminRouteEditorScreen(
                        routeId = screen.routeId,
                        onBack = { root.pop() }
                    )
                    is AdminScreen.BlockList -> AdminBlockListScreen()
                    is AdminScreen.BountyList -> AdminBountyListScreen(
                        onBountyClick = { root.push(AdminScreen.BountyDetail(it)) }
                    )
                    is AdminScreen.BountyDetail -> AdminBountyDetailScreen(
                        bountyId = screen.bountyId,
                        onBack = { root.pop() }
                    )
                    is AdminScreen.ServiceAreaMap -> AdminServiceAreaMapScreen()
                    is AdminScreen.DisputeList -> AdminDisputeListScreen(
                        onDisputeClick = { root.push(AdminScreen.DisputeDetail(it)) }
                    )
                    is AdminScreen.DisputeDetail -> AdminDisputeDetailScreen(
                        disputeId = screen.disputeId,
                        onBack = { root.pop() }
                    )
                    is AdminScreen.Analytics -> AdminAnalyticsScreen()
                    is AdminScreen.FeedbackList -> AdminFeedbackListScreen()
                    is AdminScreen.AddressChanges -> AdminAddressChangesScreen()
                    is AdminScreen.Settings -> AdminSettingsScreen()
                }
            }
        }
    }
}
