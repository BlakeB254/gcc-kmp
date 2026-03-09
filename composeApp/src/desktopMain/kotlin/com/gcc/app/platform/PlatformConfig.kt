package com.gcc.app.platform

actual fun getApiBaseUrl(): String = "http://localhost:3000"

actual fun getPlatformName(): String = "desktop"

actual fun saveToSystemCredentials(email: String, password: String) {
    // No-op on desktop
}
