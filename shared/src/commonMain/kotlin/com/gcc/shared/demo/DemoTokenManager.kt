package com.gcc.shared.demo

import com.gcc.shared.auth.TokenManager
import com.russhwolf.settings.MapSettings

/**
 * An in-memory [TokenManager] pre-loaded with the demo JWT.
 *
 * Uses [MapSettings] (a plain `Map`-backed implementation) so nothing
 * is persisted to disk, SharedPreferences, or NSUserDefaults.
 */
class DemoTokenManager : TokenManager(settings = MapSettings()) {
    init {
        saveToken(DemoData.demoToken)
        setOnboardingComplete()
    }
}
