package com.gcc.app

import androidx.compose.ui.window.ComposeUIViewController
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.gcc.app.di.appModule
import com.gcc.app.di.demoAppModule
import com.gcc.app.navigation.RootComponent
import com.gcc.shared.auth.TokenManager
import org.koin.core.context.startKoin
import org.koin.mp.KoinPlatform

/**
 * Singleton reference to the root navigation component.
 * Used by Swift AppDelegate to forward deep links and push notifications.
 */
object AppNavigator {
    var rootComponent: RootComponent? = null
        internal set

    fun handleDeepLink(uri: String) {
        rootComponent?.handleDeepLink(uri)
    }
}

fun MainViewController() = MainViewControllerWithDemo(demoMode = false)

fun MainViewControllerDemo() = MainViewControllerWithDemo(demoMode = true)

private fun MainViewControllerWithDemo(demoMode: Boolean): platform.UIKit.UIViewController {
    // Initialize Koin before composable scope (must be stable across recompositions)
    if (runCatching { KoinPlatform.getKoin() }.isFailure) {
        startKoin {
            modules(if (demoMode) demoAppModule else appModule)
        }
    }

    // Create Decompose component outside composable scope — must survive recomposition
    val lifecycle = LifecycleRegistry()
    val tokenManager = KoinPlatform.getKoin().get<TokenManager>()
    val root = RootComponent(
        componentContext = DefaultComponentContext(lifecycle = lifecycle),
        tokenManager = tokenManager,
    )
    AppNavigator.rootComponent = root

    return ComposeUIViewController {
        App(root)
    }
}
