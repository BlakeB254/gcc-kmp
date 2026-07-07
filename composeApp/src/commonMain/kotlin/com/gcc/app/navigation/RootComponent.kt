package com.gcc.app.navigation

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.DelicateDecomposeApi
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.router.stack.push
import com.arkivanov.decompose.router.stack.replaceAll
import com.arkivanov.decompose.router.stack.replaceCurrent
import com.arkivanov.decompose.value.Value
import com.gcc.app.viewmodel.LoginViewModel
import com.gcc.shared.auth.TokenManager
import org.koin.mp.KoinPlatform

@OptIn(DelicateDecomposeApi::class)
class RootComponent(
    componentContext: ComponentContext,
    private val tokenManager: TokenManager? = null,
) : ComponentContext by componentContext {

    private val navigation = StackNavigation<Screen>()

    private val initialScreen: Screen
        get() {
            val tm = tokenManager ?: return Screen.Login
            return when {
                // Valid token — gate with Face ID before entering app
                tm.isLoggedIn() && !tm.isTokenExpired() -> Screen.BiometricGate
                // Expired token or logged out BUT has saved credentials —
                // go to Face ID gate which will re-login silently via saved creds
                tm.hasBiometricToken() || tm.hasStoredCredentials() -> Screen.BiometricGate
                else -> Screen.Login
            }
        }

    val childStack: Value<ChildStack<Screen, Screen>> = childStack(
        source = navigation,
        serializer = Screen.serializer(),
        initialConfiguration = initialScreen,
        handleBackButton = true,
        childFactory = { config, _ -> config },
    )

    fun onLoginSuccess() {
        // After manual login (credentials already verified), skip biometric gate
        // and go straight to Dashboard. Face ID only gates cold app launch.
        navigation.replaceAll(Screen.Dashboard)
    }

    fun onBiometricSuccess() {
        navigation.replaceAll(Screen.Dashboard)
    }

    fun onBiometricFallback() {
        navigation.replaceAll(Screen.Login)
    }

    fun onRegisterSuccess() {
        val tm = tokenManager
        if (tm != null && !tm.isOnboardingComplete()) {
            navigation.replaceAll(Screen.Onboarding)
        } else {
            // After registration, prompt for document upload (can be skipped)
            navigation.replaceAll(Screen.DocumentUpload)
        }
    }

    fun onOnboardingComplete() {
        tokenManager?.setOnboardingComplete()
        navigation.replaceAll(Screen.Dashboard)
    }

    fun navigateToRouteDetail(routeId: Int) {
        navigation.push(Screen.RouteDetail(routeId))
    }

    fun replaceRouteDetail(routeId: Int) {
        navigation.replaceCurrent(Screen.RouteDetail(routeId))
    }

    fun navigateToActiveJob(jobId: Int, routeId: Int) {
        navigation.push(Screen.ActiveJob(jobId, routeId))
    }

    fun navigateToAchievements() {
        navigation.push(Screen.Achievements)
    }

    fun navigateToUserCard() {
        navigation.push(Screen.UserCard)
    }

    fun navigateToRegister() {
        navigation.push(Screen.Register)
    }

    fun navigateToSettings() {
        navigation.push(Screen.Settings)
    }

    fun navigateToAccountSettings() {
        navigation.push(Screen.AccountSettings)
    }

    fun navigateToLegal(type: String) {
        navigation.push(Screen.Legal(type))
    }

    fun navigateToSpot() {
        navigation.push(Screen.Spot)
    }

    fun navigateToVolunteerCleanup(reportId: Int) {
        navigation.push(Screen.VolunteerCleanup(reportId))
    }

    fun navigateToBountyList() {
        navigation.push(Screen.BountyList)
    }

    fun navigateToBountyDetail(bountyId: Int) {
        navigation.push(Screen.BountyDetail(bountyId))
    }

    fun navigateToWallet() {
        navigation.push(Screen.Wallet)
    }

    fun navigateToLeaderboard() {
        navigation.push(Screen.Leaderboard)
    }

    fun navigateToFeed() {
        navigation.push(Screen.Feed)
    }

    fun navigateToAdminWorkerDetail(workerId: Int) {
        navigation.push(Screen.AdminWorkerDetail(workerId))
    }

    fun navigateToAdminSpotCreate(lat: Double, lng: Double) {
        navigation.push(Screen.AdminSpotCreate(lat, lng))
    }

    fun navigateToAdminSpotDetail(spotId: Int) {
        navigation.push(Screen.AdminSpotDetail(spotId))
    }

    fun navigateToAdminRouteDetail(routeId: Int) {
        navigation.push(Screen.AdminRouteDetail(routeId))
    }

    fun navigateToAdminBountyDetail(bountyId: Int) {
        navigation.push(Screen.AdminBountyDetail(bountyId))
    }

    fun navigateToAdminBoundaryEditor(routeId: Int, blockId: Int) {
        navigation.push(Screen.AdminBoundaryEditor(routeId, blockId))
    }

    fun navigateToSpotVerification(reportId: Int) {
        navigation.push(Screen.SpotVerification(reportId))
    }

    fun navigateToRouteScan(routeId: Int, allowUnpublished: Boolean = false, completeJobId: Int? = null) {
        navigation.push(Screen.RouteScan(routeId, allowUnpublished, completeJobId = completeJobId))
    }

    fun navigateToRouteScanAsUser(routeId: Int, onBehalfOfWorkerId: Int, workerName: String) {
        navigation.push(
            Screen.RouteScan(
                routeId = routeId,
                allowUnpublished = true,
                onBehalfOfWorkerId = onBehalfOfWorkerId,
                onBehalfOfWorkerName = workerName,
            )
        )
    }

    fun navigateToPoints() {
        navigation.push(Screen.PointsDashboard)
    }

    fun navigateToImpact() {
        navigation.push(Screen.Impact)
    }

    fun navigateToSponsorWall() {
        navigation.push(Screen.SponsorWall)
    }

    fun navigateToDocumentUpload() {
        navigation.push(Screen.DocumentUpload)
    }

    fun onDocumentUploadComplete() {
        navigation.replaceAll(Screen.Dashboard)
    }

    fun onDocumentUploadSkip() {
        navigation.replaceAll(Screen.Dashboard)
    }

    fun navigateToVerificationGate(actionLabel: String = "claim blocks") {
        navigation.push(Screen.VerificationGate(actionLabel))
    }

    fun navigateToArticles() {
        navigation.push(Screen.Articles)
    }

    fun switchToTab(screen: Screen) {
        // Client-side admin gate for the tab path every screen actually uses.
        // The server independently re-checks the JWT role claim on every
        // /api/admin call — this just keeps non-admins out of the admin UI.
        if (screen is Screen.AdminPanel && tokenManager?.isAdmin() != true) return
        navigation.replaceAll(screen)
    }

    fun signOut() {
        tokenManager?.logout()
        navigation.replaceAll(Screen.Login)
    }

    fun navigateBack() {
        navigation.pop()
    }

    /**
     * Handle a deep link by navigating to the target screen.
     * If the user is not logged in, the deep link is ignored (they land on Login).
     * Otherwise, pushes the deep link screen on top of Dashboard.
     *
     * Auth-callback links (e.g. /auth/mobile-callback?token=...) bypass the
     * logged-in guard — by definition the user is *becoming* logged in via
     * that link. They are forwarded to LoginViewModel.handleGoogleAuthToken,
     * which exchanges the token and transitions the user into the app.
     */
    fun handleDeepLink(uri: String) {
        // 1) Auth callbacks (Google OAuth return path) — handle before any guard.
        val authToken = DeepLinkParser.extractAuthCallbackToken(uri)
        if (authToken != null) {
            runCatching {
                KoinPlatform.getKoin().get<LoginViewModel>().handleGoogleAuthToken(authToken)
            }
            return
        }

        val tm = tokenManager ?: return
        if (!tm.isLoggedIn() || tm.isTokenExpired()) return

        val screen = DeepLinkParser.parse(uri) ?: return
        navigation.push(screen)
    }
}
