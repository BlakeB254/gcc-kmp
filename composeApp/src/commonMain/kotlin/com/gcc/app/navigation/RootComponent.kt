package com.gcc.app.navigation

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.DelicateDecomposeApi
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.router.stack.push
import com.arkivanov.decompose.router.stack.replaceAll
import com.arkivanov.decompose.value.Value
import com.gcc.shared.auth.TokenManager

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
                tm.isLoggedIn() && !tm.isTokenExpired() -> Screen.BiometricGate
                tm.isLoggedIn() && tm.isTokenExpired() -> {
                    // Don't clear token here — keep it so LoginScreen can offer Face ID
                    Screen.Login
                }
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
        navigation.replaceAll(Screen.BiometricGate)
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
            navigation.replaceAll(Screen.Dashboard)
        }
    }

    fun onOnboardingComplete() {
        tokenManager?.setOnboardingComplete()
        navigation.replaceAll(Screen.Dashboard)
    }

    fun navigateToRouteDetail(routeId: Int) {
        navigation.push(Screen.RouteDetail(routeId))
    }

    fun navigateToActiveJob(jobId: Int, routeId: Int) {
        navigation.push(Screen.ActiveJob(jobId, routeId))
    }

    fun navigateToProfile() {
        navigation.push(Screen.Profile)
    }

    fun navigateToAchievements() {
        navigation.push(Screen.Achievements)
    }

    fun navigateToMap() {
        navigation.push(Screen.MapFullScreen)
    }

    fun navigateToRegister() {
        navigation.push(Screen.Register)
    }

    fun navigateToSettings() {
        navigation.push(Screen.Settings)
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

    fun navigateToAdminPanel() {
        navigation.push(Screen.AdminPanel)
    }

    fun switchToTab(screen: Screen) {
        navigation.replaceAll(screen)
    }

    fun signOut() {
        tokenManager?.clearToken()
        navigation.replaceAll(Screen.Login)
    }

    fun navigateBack() {
        navigation.pop()
    }

    /**
     * Handle a deep link by navigating to the target screen.
     * If the user is not logged in, the deep link is ignored (they land on Login).
     * Otherwise, pushes the deep link screen on top of Dashboard.
     */
    fun handleDeepLink(uri: String) {
        val tm = tokenManager ?: return
        if (!tm.isLoggedIn() || tm.isTokenExpired()) return

        val screen = DeepLinkParser.parse(uri) ?: return
        navigation.push(screen)
    }
}
