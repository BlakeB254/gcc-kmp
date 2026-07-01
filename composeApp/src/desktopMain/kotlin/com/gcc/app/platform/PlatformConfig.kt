package com.gcc.app.platform

actual fun getApiBaseUrl(): String = "http://localhost:3000"

actual fun getPlatformName(): String = "desktop"

actual fun openUrl(url: String) {
    java.awt.Desktop.getDesktop().browse(java.net.URI(url))
}

actual fun saveToSystemCredentials(email: String, password: String) {
    // No-op on desktop
}

actual fun appVersion(): String = "1.0.1 (2)"
