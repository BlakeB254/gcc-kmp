package com.gcc.app

import androidx.compose.runtime.*
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.arkivanov.decompose.extensions.compose.stack.animation.fade
import com.arkivanov.decompose.extensions.compose.stack.animation.plus
import com.arkivanov.decompose.extensions.compose.stack.animation.scale
import com.arkivanov.decompose.extensions.compose.stack.animation.stackAnimation
import com.gcc.app.navigation.RootComponent
import com.gcc.app.navigation.Screen
import com.gcc.app.ui.screens.*
import com.gcc.app.ui.screens.admin.AdminPanelScreen
import com.gcc.app.ui.screens.admin.AdminRouteDetailScreen
import com.gcc.app.ui.screens.admin.AdminSpotCreateScreen
import com.gcc.app.ui.screens.admin.AdminSpotDetailScreen
import com.gcc.app.ui.theme.GccTheme

@Composable
fun App(component: RootComponent) {
    var showSplash by remember { mutableStateOf(true) }

    GccTheme {
        if (showSplash) {
            SplashScreen(onFinished = { showSplash = false })
            return@GccTheme
        }

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
                    onAdminClick = component::navigateToAdminPanel,
                )
                is Screen.RouteDetail -> {
                    val dashVm = org.koin.compose.koinInject<com.gcc.app.viewmodel.DashboardViewModel>()
                    val dashState by dashVm.uiState.collectAsState()
                    RouteDetailScreen(
                        routeId = screen.routeId,
                        allRouteIds = dashState.routes.sortedBy { route ->
                        // Sort geographically west-to-east (by average longitude)
                        // so left arrow = westward, right arrow = eastward
                        route.geometry?.coordinates?.map { it[0] }?.average()
                            ?: route.checkpoints.map { it.coordinates.lng }.average()
                    }.map { it.id },
                        onBack = component::navigateBack,
                        onStartJob = { jobId -> component.navigateToActiveJob(jobId, screen.routeId) },
                        onNavigateToRoute = component::replaceRouteDetail,
                    )
                }
                is Screen.ActiveJob -> ActiveJobScreen(
                    jobId = screen.jobId,
                    routeId = screen.routeId,
                    onBack = component::navigateBack,
                )
                is Screen.Profile -> ProfileScreen(
                    onBack = { component.switchToTab(Screen.Dashboard) },
                    onAchievementsClick = component::navigateToAchievements,
                    onMapClick = component::navigateToMap,
                    onSettingsClick = component::navigateToSettings,
                    onAdminClick = component::navigateToAdminPanel,
                    onUserCardClick = component::navigateToUserCard,
                )
                is Screen.Achievements -> AchievementsScreen(
                    onBack = component::navigateBack,
                )
                is Screen.UserCard -> UserCardScreen(
                    onBack = component::navigateBack,
                )
                is Screen.MapFullScreen -> MapFullScreen(
                    onBack = { component.switchToTab(Screen.Dashboard) },
                    onRouteClick = component::navigateToRouteDetail,
                    onVolunteerCleanup = component::navigateToVolunteerCleanup,
                    onSpotVerification = component::navigateToSpotVerification,
                    onAdminClick = component::navigateToAdminPanel,
                    onProfileClick = component::navigateToProfile,
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
                is Screen.BiometricGate -> BiometricGateScreen(
                    onSuccess = component::onBiometricSuccess,
                    onFallback = component::onBiometricFallback,
                )
                is Screen.AdminPanel -> AdminPanelScreen(
                    onBack = component::navigateBack,
                    onNavigateToSpotCreate = component::navigateToAdminSpotCreate,
                    onNavigateToSpotDetail = component::navigateToAdminSpotDetail,
                    onNavigateToRouteDetail = component::navigateToAdminRouteDetail,
                )
                is Screen.AdminSpotCreate -> AdminSpotCreateScreen(
                    initialLat = screen.lat,
                    initialLng = screen.lng,
                    onBack = component::navigateBack,
                )
                is Screen.AdminSpotDetail -> AdminSpotDetailScreen(
                    spotId = screen.spotId,
                    onBack = component::navigateBack,
                )
                is Screen.AdminRouteDetail -> AdminRouteDetailScreen(
                    routeId = screen.routeId,
                    onBack = component::navigateBack,
                )
                is Screen.SpotVerification -> SpotVerificationScreen(
                    reportId = screen.reportId,
                    onBack = component::navigateBack,
                )
            }
        }
    }
}
