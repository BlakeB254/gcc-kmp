package com.gcc.app

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.decompose.extensions.compose.lifecycle.LifecycleController
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.gcc.app.di.appModule
import com.gcc.app.di.offlineAppModule
import com.gcc.app.navigation.RootComponent
import com.gcc.shared.auth.TokenManager
import org.koin.core.context.startKoin
import org.koin.java.KoinJavaComponent.getKoin
import javax.swing.SwingUtilities

fun main(args: Array<String> = emptyArray()) {
    val isDemo = "--demo" in args || System.getenv("GCC_DEMO") == "true"

    startKoin {
        modules(if (isDemo) offlineAppModule else appModule)
    }

    val lifecycle = LifecycleRegistry()
    val tokenManager = getKoin().get<TokenManager>()

    // Decompose requires RootComponent to be created on the main/UI thread
    val root = runOnUiThread {
        RootComponent(
            componentContext = DefaultComponentContext(lifecycle = lifecycle),
            tokenManager = tokenManager,
        )
    }

    application {
        val windowState = rememberWindowState(width = 420.dp, height = 800.dp)
        LifecycleController(lifecycle, windowState)

        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = if (isDemo) "GCC App [DEMO]" else "GCC App",
        ) {
            App(root)
        }
    }
}

/**
 * Runs a block on the AWT Event Dispatch Thread (Swing main thread)
 * and waits for the result. Decompose checks that component creation
 * happens on this thread.
 */
private fun <T> runOnUiThread(block: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) {
        return block()
    }

    var result: T? = null
    SwingUtilities.invokeAndWait { result = block() }
    @Suppress("UNCHECKED_CAST")
    return result as T
}
