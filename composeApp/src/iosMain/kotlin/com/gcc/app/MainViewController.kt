package com.gcc.app

import androidx.compose.ui.window.ComposeUIViewController
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.gcc.app.di.appModule
import com.gcc.app.di.offlineAppModule
import com.gcc.app.navigation.RootComponent
import com.gcc.app.platform.flushPendingCrashReport
import com.gcc.app.platform.installCrashReporting
import com.gcc.app.platform.LocationTrackingController
import com.gcc.app.platform.LocationProvider
import com.gcc.shared.api.GccApiClient
import com.gcc.shared.auth.TokenManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    // Crash reporting first: installs the Kotlin fatal-exception hook before
    // anything else can crash during init.
    installCrashReporting()

    // Initialize Koin before composable scope (must be stable across recompositions)
    if (runCatching { KoinPlatform.getKoin() }.isFailure) {
        startKoin {
            modules(if (demoMode) offlineAppModule else appModule)
        }
    }

    // Now that Koin is up, flush any previous-session crash into the 🐞 panel
    // log and transmit it to the backend. Fire-and-forget: never blocks startup.
    // getOrNull, not get -- offlineAppModule never registers a real GccApiClient
    // (deliberate), so this must degrade to "skip the flush" there, not crash.
    KoinPlatform.getKoin().getOrNull<GccApiClient>()?.let { apiClient ->
        CoroutineScope(Dispatchers.Default).launch { flushPendingCrashReport(apiClient) }
    }

    // Create Decompose component outside composable scope — must survive recomposition
    val lifecycle = LifecycleRegistry()
    val tokenManager = KoinPlatform.getKoin().get<TokenManager>()
    LocationTrackingController.initLocationProvider(KoinPlatform.getKoin().get<LocationProvider>())
    val root = RootComponent(
        componentContext = DefaultComponentContext(lifecycle = lifecycle),
        tokenManager = tokenManager,
    )
    AppNavigator.rootComponent = root

    return ComposeUIViewController {
        App(root)
    }
}
