package com.gcc.app

import androidx.compose.ui.window.ComposeUIViewController
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.gcc.app.di.appModule
import com.gcc.app.navigation.RootComponent
import com.gcc.shared.auth.TokenManager
import org.koin.core.context.startKoin
import org.koin.core.context.GlobalContext

fun MainViewController() = ComposeUIViewController {
    // Initialize Koin if not already started
    if (GlobalContext.getOrNull() == null) {
        startKoin {
            modules(appModule)
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
