package com.gcc.app.platform

/**
 * Platform-specific API base URL.
 *
 * - Android & iOS: production `https://api.globalcommunitycleaners.org`
 *   (served by the Next.js site) — devices always talk to prod.
 * - Desktop (dev-only): `http://localhost:7350`, the local Next.js dev
 *   server (`cd site && npm run dev`).
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

/** Returns the app version string, e.g. "1.0.1 (2)". Never throws. */
expect fun appVersion(): String
