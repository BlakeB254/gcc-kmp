package com.gcc.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.arkivanov.decompose.extensions.compose.stack.animation.fade
import com.arkivanov.decompose.extensions.compose.stack.animation.plus
import com.arkivanov.decompose.extensions.compose.stack.animation.scale
import com.arkivanov.decompose.extensions.compose.stack.animation.stackAnimation
import com.gcc.app.di.SessionExpiredSignal
import com.gcc.app.navigation.RootComponent
import com.gcc.app.navigation.Screen
import com.gcc.app.platform.FirstRunStore
import com.gcc.app.ui.screens.*
import com.gcc.app.ui.screens.admin.AdminPanelScreen
import com.gcc.app.ui.screens.admin.AdminJobListScreen
import com.gcc.app.ui.screens.admin.AdminBoundaryEditorScreen
import com.gcc.app.ui.screens.admin.AdminRouteDetailScreen
import com.gcc.app.ui.screens.admin.AdminSpotCreateScreen
import com.gcc.app.ui.screens.admin.AdminSpotDetailScreen
import com.gcc.app.ui.theme.GccGreen
import com.gcc.app.ui.screens.admin.AdminBountyDetailScreen
import com.gcc.app.ui.screens.admin.AdminWorkerDetailScreen
import com.gcc.app.ui.theme.GccTheme
import com.gcc.shared.geo.routeCenterCoordinates
import org.koin.mp.KoinPlatform

@Composable
fun App(component: RootComponent) {
    var showSplash by remember { mutableStateOf(true) }

    // §5.2 splash gating: full sequence only when the intro was never seen
    // (first download); returning users get the ~1.5s logo hold. Resolved
    // from Koin — same lookup pattern RootComponent uses.
    val firstRunStore: FirstRunStore? = remember {
        runCatching { KoinPlatform.getKoin().get<FirstRunStore>() }.getOrNull()
    }

    // Observe forced-logout signal from API 401/403
    val sessionExpired by SessionExpiredSignal.expired.collectAsState()
    LaunchedEffect(sessionExpired) {
        if (sessionExpired) {
            SessionExpiredSignal.reset()
            component.signOut()
        }
    }

    GccTheme {
        if (showSplash) {
            SplashScreen(
                shortMode = firstRunStore?.introSeen == true,
                onFinished = { showSplash = false },
            )
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
                is Screen.Intro -> IntroScreen(
                    onComplete = component::onIntroComplete,
                )
                is Screen.Dashboard -> DashboardScreen(
                    onRouteClick = component::navigateToRouteDetail,
                    onActiveJobClick = component::navigateToActiveJob,
                    onProfileClick = { component.switchToTab(Screen.Profile) },
                    onMapClick = { component.switchToTab(Screen.MapFullScreen) },
                    onSpotClick = component::navigateToSpot,
                    onBountyListClick = component::navigateToBountyList,
                    onWalletClick = component::navigateToWallet,
                    onLeaderboardClick = component::navigateToLeaderboard,
                    onFeedClick = component::navigateToFeed,
                    onAchievementsClick = component::navigateToAchievements,
                    onSettingsClick = component::navigateToSettings,
                    onAdminClick = { component.switchToTab(Screen.AdminPanel) },
                    onPointsClick = component::navigateToPoints,
                    onImpactClick = component::navigateToImpact,
                    onSponsorWallClick = component::navigateToSponsorWall,
                )
                is Screen.RouteDetail -> {
                    val dashVm = org.koin.compose.koinInject<com.gcc.app.viewmodel.DashboardViewModel>()
                    val dashState by dashVm.uiState.collectAsState()
                    RouteDetailScreen(
                        routeId = screen.routeId,
                        allRouteIds = dashState.routes.sortedBy { route ->
                            // Sort geographically west-to-east so left arrow =
                            // westward, right arrow = eastward.
                            route.routeCenterCoordinates()?.lng ?: 0.0
                    }.map { it.id },
                        onBack = component::navigateBack,
                        onStartJob = { jobId -> component.navigateToActiveJob(jobId, screen.routeId) },
                        onNavigateToRoute = component::replaceRouteDetail,
                        onStartScan = { routeId -> component.navigateToRouteScan(routeId) },
                        onVerificationGate = component::navigateToVerificationGate,
                    )
                }
                is Screen.ActiveJob -> ActiveJobScreen(
                    jobId = screen.jobId,
                    routeId = screen.routeId,
                    onBack = component::navigateBack,
                    onStartScan = { routeId ->
                        component.navigateToRouteScan(routeId, allowUnpublished = true, completeJobId = screen.jobId)
                    },
                )
                is Screen.Profile -> ProfileScreen(
                    onBack = { component.switchToTab(Screen.Dashboard) },
                    onAchievementsClick = component::navigateToAchievements,
                    onMapClick = { component.switchToTab(Screen.MapFullScreen) },
                    onSettingsClick = component::navigateToSettings,
                    onAdminClick = { component.switchToTab(Screen.AdminPanel) },
                    onUserCardClick = component::navigateToUserCard,
                    onPointsClick = component::navigateToPoints,
                    onImpactClick = component::navigateToImpact,
                    onVerifyDocuments = component::navigateToDocumentUpload,
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
                    onBountyClick = component::navigateToBountyDetail,
                    onVolunteerCleanup = component::navigateToVolunteerCleanup,
                    onSpotVerification = component::navigateToSpotVerification,
                    onActiveJobClick = component::navigateToActiveJob,
                    onAdminClick = { component.switchToTab(Screen.AdminPanel) },
                    onProfileClick = { component.switchToTab(Screen.Profile) },
                    onNavigateToSpot = component::navigateToSpot,
                )
                is Screen.Settings -> SettingsScreen(
                    onBack = component::navigateBack,
                    onNavigateToLegal = component::navigateToLegal,
                    onSignOut = component::signOut,
                    onNavigateToAccountSettings = component::navigateToAccountSettings,
                )
                is Screen.AccountSettings -> AccountSettingsScreen(
                    onBack = component::navigateBack,
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
                    onVolunteerCleanup = component::navigateToVolunteerCleanup,
                )
                is Screen.Wallet -> WalletScreen(
                    onBack = component::navigateBack,
                )
                is Screen.Leaderboard -> LeaderboardScreen(
                    onBack = component::navigateBack,
                    onAchievementsClick = component::navigateToAchievements,
                    onPointsClick = component::navigateToPoints,
                )
                is Screen.Feed -> FeedScreen(
                    onBack = component::navigateBack,
                    onNavigateToArticles = component::navigateToArticles,
                    onImpactClick = component::navigateToImpact,
                    onSponsorWallClick = component::navigateToSponsorWall,
                )
                is Screen.BiometricGate -> BiometricGateScreen(
                    onSuccess = component::onBiometricSuccess,
                    onFallback = component::onBiometricFallback,
                )
                is Screen.AdminPanel -> AdminPanelScreen(
                    onLogout = component::signOut,
                    onNavigateToJobList = component::navigateToAdminJobList,
                    onNavigateToSpotCreate = component::navigateToAdminSpotCreate,
                    onNavigateToSpotDetail = component::navigateToAdminSpotDetail,
                    onNavigateToRouteDetail = component::navigateToAdminRouteDetail,
                    onNavigateToWorkerDetail = component::navigateToAdminWorkerDetail,
                    onNavigateToBountyDetail = component::navigateToAdminBountyDetail,
                    onNavigateToDashboard = { component.switchToTab(Screen.Dashboard) },
                    onNavigateToMap = { component.switchToTab(Screen.MapFullScreen) },
                    onNavigateToProfile = { component.switchToTab(Screen.Profile) },
                )
                // Pushed, not switchToTab'd — so the back stack is non-empty here
                // and popping is the right behaviour.
                is Screen.AdminJobList -> AdminJobListScreen(
                    title = screen.title,
                    status = screen.status,
                    onBack = component::navigateBack,
                )
                is Screen.AdminWorkerDetail -> AdminWorkerDetailScreen(
                    workerId = screen.workerId,
                    onBack = component::navigateBack,
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
                    onBeginAsUser = { routeId, workerId, workerName ->
                        component.navigateToRouteScanAsUser(routeId, workerId, workerName)
                    },
                    onEditInnerBoundaries = component::navigateToAdminBoundaryEditor,
                )
                is Screen.AdminBoundaryEditor -> AdminBoundaryEditorScreen(
                    routeId = screen.routeId,
                    blockId = screen.blockId,
                    onBack = component::navigateBack,
                )
                is Screen.AdminBountyDetail -> AdminBountyDetailScreen(
                    bountyId = screen.bountyId,
                    onBack = component::navigateBack,
                )
                is Screen.SpotVerification -> SpotVerificationScreen(
                    reportId = screen.reportId,
                    onBack = component::navigateBack,
                )
                is Screen.RouteScan -> {
                    val apiClient = org.koin.compose.koinInject<com.gcc.shared.api.GccApiClient>()
                    val dashVm = org.koin.compose.koinInject<com.gcc.app.viewmodel.DashboardViewModel>()
                    val dashState by dashVm.uiState.collectAsState()
                    var route by remember(screen.routeId) {
                        mutableStateOf(dashState.routes.find { it.id == screen.routeId }
                            ?: dashState.assignedRoutesById[screen.routeId])
                    }
                    var loadError by remember(screen.routeId) { mutableStateOf<String?>(null) }
                    LaunchedEffect(screen.routeId) {
                        if (route == null) {
                            route = try {
                                apiClient.getRoute(screen.routeId)
                            } catch (e: Exception) {
                                loadError = e.message ?: "Failed to load route"
                                null
                            }
                        }
                    }
                    if (route != null) {
                        RouteScanScreen(
                            route = route!!,
                            allowUnpublished = screen.allowUnpublished,
                            onBehalfOfWorkerId = screen.onBehalfOfWorkerId,
                            onBehalfOfWorkerName = screen.onBehalfOfWorkerName,
                            completeJobId = screen.completeJobId,
                            onBack = component::navigateBack,
                            onComplete = component::navigateBack,
                        )
                    } else if (loadError != null) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(loadError ?: "Failed to load route")
                        }
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = GccGreen)
                        }
                    }
                }
                is Screen.PointsDashboard -> PointsDashboardScreen(
                    onBack = component::navigateBack,
                )
                is Screen.Impact -> ImpactScreen(
                    onBack = component::navigateBack,
                )
                is Screen.SponsorWall -> SponsorWallScreen(
                    onBack = component::navigateBack,
                )
                is Screen.DocumentUpload -> DocumentUploadScreen(
                    onComplete = component::onDocumentUploadComplete,
                    onSkip = component::onDocumentUploadSkip,
                )
                is Screen.VerificationGate -> {
                    val dashVm = org.koin.compose.koinInject<com.gcc.app.viewmodel.DashboardViewModel>()
                    val dashState by dashVm.uiState.collectAsState()
                    val verification = dashState.worker?.verification
                        ?: com.gcc.shared.model.WorkerVerification()
                    VerificationGateScreen(
                        verification = verification,
                        actionLabel = screen.actionLabel,
                        onUploadDocuments = component::navigateToDocumentUpload,
                        onBack = component::navigateBack,
                    )
                }
                is Screen.Articles -> ArticlesScreen(
                    onBack = component::navigateBack,
                )
            }
        }
    }
}
