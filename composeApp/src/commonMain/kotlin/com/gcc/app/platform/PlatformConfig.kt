package com.gcc.app.platform

/**
 * Platform-specific API base URL.
 *
 * Each platform resolves to the correct localhost address:
 * - Android emulator: 10.0.2.2 (maps to host machine)
 * - iOS simulator: localhost
 * - Desktop: localhost
 */
expect fun getApiBaseUrl(): String

/** Returns "android", "ios", or "desktop" for push notification token registration. */
expect fun getPlatformName(): String

/**
 * Save login credentials to the system credential store (iOS Passwords app).
 * On iOS, uses SecAddSharedWebCredential to store in the Passwords app.
 * On other platforms, this is a no-op.
 */
expect fun saveToSystemCredentials(email: String, password: String)

/** Open a URL in the system browser (Safari on iOS, Chrome on Android). */
expect fun openUrl(url: String)
