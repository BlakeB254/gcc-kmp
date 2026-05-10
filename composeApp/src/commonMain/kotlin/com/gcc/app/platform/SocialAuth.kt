package com.gcc.app.platform

/**
 * Result of a social sign-in attempt.
 */
data class SocialAuthResult(
    val identityToken: String,
    val fullName: String? = null,
    val email: String? = null,
    val nonce: String? = null,
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

/**
 * Google Sign-In — native on iOS via GoogleSignIn SPM package; no-op
 * elsewhere. Returns SocialAuthResult with Google's idToken, or null if
 * cancelled / unavailable / SDK not yet configured.
 */
expect suspend fun signInWithGoogle(): SocialAuthResult?

/**
 * Whether Google Sign-In is wired up on this platform. False until the
 * Swift bridge has registered itself (GoogleSignIn SPM dep added,
 * iOS client ID configured).
 */
expect fun isGoogleSignInAvailable(): Boolean
