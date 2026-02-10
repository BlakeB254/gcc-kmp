package com.gcc.admin.navigation

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.push
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.router.stack.replaceAll
import com.arkivanov.decompose.value.Value

/**
 * Root navigation component for the admin app.
 *
 * Uses Decompose's ChildStack with a sidebar navigation pattern:
 * the sidebar is always visible, and clicking a nav item replaces
 * the entire stack with the new screen.
 */
class AdminRootComponent(
    componentContext: ComponentContext,
) : ComponentContext by componentContext {

    private val navigation = StackNavigation<AdminScreen>()

    val childStack: Value<ChildStack<AdminScreen, AdminScreen>> = childStack(
        source = navigation,
        serializer = AdminScreen.serializer(),
        initialConfiguration = AdminScreen.Login,
        handleBackButton = true,
        childFactory = { screen, _ -> screen },
    )

    fun navigateTo(screen: AdminScreen) {
        navigation.replaceAll(screen)
    }

    fun push(screen: AdminScreen) {
        @OptIn(com.arkivanov.decompose.DelicateDecomposeApi::class)
        navigation.push(screen)
    }

    fun pop() {
        navigation.pop()
    }

    fun onLoginSuccess() {
        navigation.replaceAll(AdminScreen.Dashboard)
    }
}
