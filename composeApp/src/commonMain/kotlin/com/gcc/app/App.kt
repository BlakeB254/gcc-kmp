package com.gcc.app

import androidx.compose.runtime.Composable
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.arkivanov.decompose.extensions.compose.stack.animation.fade
import com.arkivanov.decompose.extensions.compose.stack.animation.plus
import com.arkivanov.decompose.extensions.compose.stack.animation.scale
import com.arkivanov.decompose.extensions.compose.stack.animation.stackAnimation
import com.gcc.app.navigation.RootComponent
import com.gcc.app.navigation.Screen
import com.gcc.app.ui.screens.*
import com.gcc.app.ui.theme.GccTheme

@Composable
fun App(component: RootComponent) {
    GccTheme {
        Children(
            stack = component.childStack,
            animation = stackAnimation(fade() + scale()),
        ) { child ->
            when (val screen = child.instance) {
                is Screen.Login -> LoginScreen(
                    onLoginSuccess = component::onLoginSuccess,
                    onRegisterClick = component::navigateToRegister,
                )
                is Screen.Register -> RegisterScreen(
                    onLoginClick = component::navigateBack,
                    onRegisterSuccess = component::onRegisterSuccess,
                )
                is Screen.Onboarding -> OnboardingScreen(
                    onComplete = component::onOnboardingComplete,
                )
                is Screen.Dashboard -> DashboardScreen(
                    onRouteClick = component::navigateToRouteDetail,
                    onProfileClick = component::navigateToProfile,
                    onMapClick = component::navigateToMap,
                    onSpotClick = component::navigateToSpot,
                    onBountyListClick = component::navigateToBountyList,
                    onWalletClick = component::navigateToWallet,
                    onLeaderboardClick = component::navigateToLeaderboard,
                    onFeedClick = component::navigateToFeed,
                    onAchievementsClick = component::navigateToAchievements,
                    onSettingsClick = component::navigateToSettings,
                )
                is Screen.RouteDetail -> RouteDetailScreen(
                    routeId = screen.routeId,
                    onBack = component::navigateBack,
                    onStartJob = { jobId -> component.navigateToActiveJob(jobId, screen.routeId) },
                )
                is Screen.ActiveJob -> ActiveJobScreen(
                    jobId = screen.jobId,
                    routeId = screen.routeId,
                    onBack = component::navigateBack,
                )
                is Screen.Profile -> ProfileScreen(
                    onBack = component::navigateBack,
                    onAchievementsClick = component::navigateToAchievements,
                    onMapClick = component::navigateToMap,
                    onSettingsClick = component::navigateToSettings,
                )
                is Screen.Achievements -> AchievementsScreen(
                    onBack = component::navigateBack,
                )
                is Screen.MapFullScreen -> MapFullScreen(
                    onBack = component::navigateBack,
                    onRouteClick = component::navigateToRouteDetail,
                )
                is Screen.Settings -> SettingsScreen(
                    onBack = component::navigateBack,
                    onNavigateToLegal = component::navigateToLegal,
                    onSignOut = component::signOut,
                )
                is Screen.Legal -> LegalScreen(
                    type = screen.type,
                    onBack = component::navigateBack,
                )
                is Screen.Spot -> SpotScreen(
                    onBack = component::navigateBack,
                    onVolunteerCleanup = component::navigateToVolunteerCleanup,
                    onNavigateToActiveJob = component::navigateToActiveJob,
                )
                is Screen.VolunteerCleanup -> VolunteerCleanupScreen(
                    reportId = screen.reportId,
                    onBack = component::navigateBack,
                )
                is Screen.BountyList -> BountyListScreen(
                    onBack = component::navigateBack,
                    onBountyClick = component::navigateToBountyDetail,
                )
                is Screen.BountyDetail -> BountyDetailScreen(
                    bountyId = screen.bountyId,
                    onBack = component::navigateBack,
                    onNavigateToActiveJob = component::navigateToActiveJob,
                )
                is Screen.Wallet -> WalletScreen(
                    onBack = component::navigateBack,
                )
                is Screen.Leaderboard -> LeaderboardScreen(
                    onBack = component::navigateBack,
                )
                is Screen.Feed -> FeedScreen(
                    onBack = component::navigateBack,
                )
            }
        }
    }
}
