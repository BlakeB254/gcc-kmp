package com.gcc.app

import androidx.compose.ui.window.ComposeUIViewController
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.gcc.app.navigation.RootComponent

fun MainViewController() = ComposeUIViewController {
    val lifecycle = LifecycleRegistry()
    val root = RootComponent(DefaultComponentContext(lifecycle = lifecycle))
    App(root)
}
