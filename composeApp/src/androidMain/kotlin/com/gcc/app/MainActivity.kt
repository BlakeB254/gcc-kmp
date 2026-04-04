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
import com.gcc.app.platform.PushNotificationManager
import com.gcc.app.platform.initSecureStorageContext
import com.gcc.app.platform.PlatformUrlOpener
import com.gcc.app.platform.ShareManager
import com.gcc.shared.auth.TokenManager
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin

class MainActivity : FragmentActivity() {

    private var rootComponent: RootComponent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize platform providers that need Android context
        initSecureStorageContext(this)
        LocationProvider.initContext(this)
        LocationTrackingController.initContext(this)
        ShareManager.initContext(this)
        PlatformUrlOpener.initContext(this)
        PushNotificationManager.initContext(this)
        BiometricAuth.initActivity(this)

        val isDemo = intent.getBooleanExtra("GCC_DEMO", false)
            || System.getenv("GCC_DEMO") == "true"
            || (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

        // Initialize Koin if not already started (matches desktop Main.kt and iOS MainViewController.kt)
        if (GlobalContext.getOrNull() == null) {
            startKoin {
                modules(if (isDemo) offlineAppModule else appModule)
            }
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
