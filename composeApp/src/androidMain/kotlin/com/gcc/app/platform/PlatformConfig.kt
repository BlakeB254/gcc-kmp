package com.gcc.app.platform

actual fun getApiBaseUrl(): String = "https://api.globalcommunitycleaners.org"

actual fun getPlatformName(): String = "android"

actual fun saveToSystemCredentials(email: String, password: String) {
    // No-op on Android (credentials stored in app Keychain only)
}
