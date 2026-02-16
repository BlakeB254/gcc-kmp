package com.gcc.admin.navigation

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

/**
 * Root navigation component for the admin desktop app.
 *
 * Uses Decompose's ChildStack with a sidebar navigation pattern:
 * - Sidebar clicks replace the entire stack (top-level navigation).
 * - Detail screens are pushed onto the stack (drill-down navigation).
 * - Login state is checked on startup to determine the initial screen.
 */
@OptIn(DelicateDecomposeApi::class)
class AdminRootComponent(
    componentContext: ComponentContext,
    private val tokenManager: TokenManager? = null,
) : ComponentContext by componentContext {

    private val navigation = StackNavigation<AdminScreen>()

    private val initialScreen: AdminScreen
        get() {
            val tm = tokenManager ?: return AdminScreen.Login
            return when {
                tm.isLoggedIn() && !tm.isTokenExpired() -> AdminScreen.Dashboard
                tm.isLoggedIn() && tm.isTokenExpired() -> {
                    tm.clearToken()
                    AdminScreen.Login
                }
                else -> AdminScreen.Login
            }
        }

    val childStack: Value<ChildStack<AdminScreen, AdminScreen>> = childStack(
        source = navigation,
        serializer = AdminScreen.serializer(),
        initialConfiguration = initialScreen,
        handleBackButton = true,
        childFactory = { screen, _ -> screen },
    )

    /** After successful login, replace the stack with the Dashboard. */
    fun onLoginSuccess() {
        navigation.replaceAll(AdminScreen.Dashboard)
    }

    /** Sidebar navigation: replaces the entire stack with the target screen. */
    fun navigateTo(screen: AdminScreen) {
        navigation.replaceAll(screen)
    }

    /** Drill-down navigation: pushes a detail screen onto the stack. */
    fun push(screen: AdminScreen) {
        navigation.push(screen)
    }

    /** Navigate back (pop the top screen from the stack). */
    fun navigateBack() {
        navigation.pop()
    }

    /** Sign out: clear the token and return to login. */
    fun signOut() {
        tokenManager?.clearToken()
        navigation.replaceAll(AdminScreen.Login)
    }
}
