package com.gcc.app

import androidx.compose.ui.window.ComposeUIViewController
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.gcc.app.di.appModule
import com.gcc.app.di.demoAppModule
import com.gcc.app.navigation.RootComponent
import com.gcc.shared.auth.TokenManager
import org.koin.core.context.startKoin
import org.koin.core.context.GlobalContext

fun MainViewController() = MainViewControllerWithDemo(demoMode = false)

fun MainViewControllerDemo() = MainViewControllerWithDemo(demoMode = true)

private fun MainViewControllerWithDemo(demoMode: Boolean) = ComposeUIViewController {
    // Initialize Koin if not already started
    if (GlobalContext.getOrNull() == null) {
        startKoin {
            modules(if (demoMode) demoAppModule else appModule)
        }
    }

    val lifecycle = LifecycleRegistry()
    val tokenManager = GlobalContext.get().get<TokenManager>()
    val root = RootComponent(
        componentContext = DefaultComponentContext(lifecycle = lifecycle),
        tokenManager = tokenManager,
    )
    App(root)
}
