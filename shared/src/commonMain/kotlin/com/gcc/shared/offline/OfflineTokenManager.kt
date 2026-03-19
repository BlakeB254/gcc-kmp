package com.gcc.shared.offline

import com.gcc.shared.auth.TokenManager


/**
 * An in-memory [TokenManager] pre-loaded with the offline JWT.
 *
 * Uses [MapSettings] (a plain `Map`-backed implementation) so nothing
 * is persisted to disk, SharedPreferences, or NSUserDefaults.
 */
class OfflineTokenManager : TokenManager(settings = MemorySettings()) {
    init {
        saveToken(SeedData.offlineToken)
        setOnboardingComplete()
    }
}
