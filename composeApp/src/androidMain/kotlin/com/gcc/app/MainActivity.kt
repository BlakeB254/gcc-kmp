package com.gcc.app

import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.arkivanov.decompose.defaultComponentContext
import com.gcc.app.di.appModule
import com.gcc.app.di.demoAppModule
import com.gcc.app.navigation.RootComponent
import com.gcc.shared.auth.TokenManager
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val isDemo = intent.getBooleanExtra("GCC_DEMO", false)
            || System.getenv("GCC_DEMO") == "true"
            || (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

        // Initialize Koin if not already started (matches desktop Main.kt and iOS MainViewController.kt)
        if (GlobalContext.getOrNull() == null) {
            startKoin {
                modules(if (isDemo) demoAppModule else appModule)
            }
        }

        val tokenManager = GlobalContext.get().get<TokenManager>()
        val root = RootComponent(
            componentContext = defaultComponentContext(),
            tokenManager = tokenManager,
        )

        setContent {
            App(root)
        }
    }
}
