package com.gcc.app.platform

/**
 * Result of a social sign-in attempt.
 */
data class SocialAuthResult(
    val identityToken: String,
    val fullName: String? = null,
    val email: String? = null,
)

/**
 * Apple Sign-In — native on iOS, no-op on Android/Desktop.
 * Returns SocialAuthResult with Apple's identity token, or null if cancelled/unavailable.
 */
expect suspend fun signInWithApple(): SocialAuthResult?

/**
 * Whether Apple Sign-In is available on this platform.
 */
expect fun isAppleSignInAvailable(): Boolean
