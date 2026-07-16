package com.gcc.app

import androidx.compose.ui.window.ComposeUIViewController
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.gcc.app.di.appModule
import com.gcc.app.di.offlineAppModule
import com.gcc.app.navigation.RootComponent
import com.gcc.app.platform.DiagnosticsLog
import com.gcc.app.platform.EnvScanSessionUploader
import com.gcc.app.platform.capture.CaptureChunkUploader
import com.gcc.app.platform.flushPendingCrashReport
import com.gcc.app.platform.installCrashReporting
import com.gcc.app.platform.LocationTrackingController
import com.gcc.app.platform.LocationProvider
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplicationDidBecomeActiveNotification
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

    // Scan-artifact catch-up on EVERY app activation, not just scan-screen
    // open: the manifest/chunk retry queues used to flush only from the scan
    // ViewModels' init (a lazy Koin single — reopening the app to the
    // dashboard flushed nothing, and foregrounding a live process never
    // re-runs init at all). A session whose manifest failed at scan end
    // (87x3ug postmortem: transient LTE blip seconds after the event log
    // landed) then sat invisible to the DGX indexer until the NEXT scan.
    // UIApplicationDidBecomeActiveNotification fires on cold launch AND every
    // return to foreground; both uploaders serialize internally (mutex) and
    // no-op on an empty queue, so overlapping kicks are harmless. getOrNull:
    // offlineAppModule registers neither uploader (no real GccApiClient).
    run {
        val koin = KoinPlatform.getKoin()
        val sessionUploader = koin.getOrNull<EnvScanSessionUploader>()
        val chunkUploader = koin.getOrNull<CaptureChunkUploader>()
        if (sessionUploader != null || chunkUploader != null) {
            val flushScope = CoroutineScope(Dispatchers.Default)
            NSNotificationCenter.defaultCenter.addObserverForName(
                name = UIApplicationDidBecomeActiveNotification,
                `object` = null,
                queue = null,
            ) { _ ->
                flushScope.launch {
                    try {
                        sessionUploader?.flushPending()
                        chunkUploader?.flushPending()
                    } catch (e: Exception) {
                        DiagnosticsLog.error("env-capture", "foreground flush failed", e)
                    }
                }
            }
        }
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
