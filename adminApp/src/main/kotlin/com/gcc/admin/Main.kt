package com.gcc.admin

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.decompose.extensions.compose.lifecycle.LifecycleController
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.gcc.admin.di.adminModule
import com.gcc.admin.navigation.AdminRootComponent
import org.koin.core.context.startKoin
import org.koin.java.KoinJavaComponent.getKoin
import javax.swing.SwingUtilities

fun main() {
    startKoin {
        modules(adminModule)
    }

    val lifecycle = LifecycleRegistry()

    val root = runOnUiThread {
        AdminRootComponent(
            componentContext = DefaultComponentContext(lifecycle = lifecycle),
        )
    }

    application {
        val windowState = rememberWindowState(width = 1400.dp, height = 900.dp)
        LifecycleController(lifecycle, windowState)

        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "GCC Admin Dashboard",
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
