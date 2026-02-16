package com.gcc.admin

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.decompose.extensions.compose.lifecycle.LifecycleController
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.gcc.admin.di.adminModule
import com.gcc.admin.di.demoAdminModule
import com.gcc.admin.navigation.AdminRootComponent
import com.gcc.shared.auth.TokenManager
import org.koin.core.context.startKoin
import org.koin.java.KoinJavaComponent.getKoin
import javax.swing.SwingUtilities

fun main(args: Array<String> = emptyArray()) {
    val isDemo = "--demo" in args || System.getenv("GCC_DEMO") == "true"

    startKoin {
        modules(if (isDemo) demoAdminModule else adminModule)
    }

    val lifecycle = LifecycleRegistry()
    val tokenManager = getKoin().get<TokenManager>()

    val root = runOnUiThread {
        AdminRootComponent(
            componentContext = DefaultComponentContext(lifecycle = lifecycle),
            tokenManager = tokenManager,
        )
    }

    application {
        val windowState = rememberWindowState(width = 1440.dp, height = 900.dp)
        LifecycleController(lifecycle, windowState)

        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = if (isDemo) "GCC Admin [DEMO]" else "GCC Admin",
        ) {
            AdminApp(root)
        }
    }
}

private fun <T> runOnUiThread(block: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) {
        return block()
    }
    var result: T? = null
    SwingUtilities.invokeAndWait { result = block() }
    @Suppress("UNCHECKED_CAST")
    return result as T
}
