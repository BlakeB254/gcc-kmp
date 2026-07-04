package com.gcc.app

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import com.arkivanov.decompose.defaultComponentContext
import com.gcc.app.di.appModule
import com.gcc.app.di.offlineAppModule
import com.gcc.app.navigation.RootComponent
import com.gcc.app.platform.BiometricAuth
import com.gcc.app.platform.LocationProvider
import com.gcc.app.platform.LocationTrackingController
import com.gcc.app.platform.PermissionManager
import com.gcc.app.platform.PushNotificationManager
import com.gcc.app.platform.initCrashReportingContext
import com.gcc.app.platform.installCrashReporting
import com.gcc.app.platform.flushPendingCrashReport
import com.gcc.app.platform.initSecureStorageContext
import com.gcc.app.platform.initSqlDriverContext
import com.gcc.app.platform.PlatformUrlOpener
import com.gcc.app.platform.ShareManager
import com.gcc.shared.api.GccApiClient
import com.gcc.shared.auth.TokenManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin

class MainActivity : FragmentActivity() {

    private var rootComponent: RootComponent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Crash reporting first: installs the uncaught-exception hook before
        // anything else (including the other initXContext calls below) can crash.
        initCrashReportingContext(this)
        installCrashReporting()

        // Initialize platform providers that need Android context
        initSecureStorageContext(this)
        initSqlDriverContext(this)
        LocationProvider.initContext(this)
        LocationTrackingController.initContext(this)
        ShareManager.initContext(this)
        PlatformUrlOpener.initContext(this)
        PushNotificationManager.initContext(this)
        BiometricAuth.initActivity(this)
        PermissionManager.initActivity(this)

        val isDemo = intent.getBooleanExtra("GCC_DEMO", false)
            || System.getenv("GCC_DEMO") == "true"
            || (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

        // Initialize Koin if not already started (matches desktop Main.kt and iOS MainViewController.kt)
        if (GlobalContext.getOrNull() == null) {
            startKoin {
                modules(if (isDemo) offlineAppModule else appModule)
            }
        }

        // Now that Koin is up, flush any previous-session crash into the 🐞 panel
        // log and transmit it to the backend. Fire-and-forget: never blocks startup.
        // getOrNull, not get -- offlineAppModule never registers a real GccApiClient
        // (deliberate), so this must degrade to "skip the flush" there, not crash.
        GlobalContext.get().getOrNull<GccApiClient>()?.let { apiClient ->
            CoroutineScope(Dispatchers.Default).launch { flushPendingCrashReport(apiClient) }
        }

        val tokenManager = GlobalContext.get().get<TokenManager>()
        val root = RootComponent(
            componentContext = defaultComponentContext(),
            tokenManager = tokenManager,
        )
        rootComponent = root

        // Handle deep link from cold start
        intent?.data?.toString()?.let { uri ->
            root.handleDeepLink(uri)
        }

        setContent {
            App(root)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Handle deep link when activity is already running (singleTask launch mode)
        intent.data?.toString()?.let { uri ->
            rootComponent?.handleDeepLink(uri)
        }
    }
}
