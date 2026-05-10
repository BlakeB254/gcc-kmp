package com.gcc.app.platform

actual fun isAppleSignInAvailable(): Boolean = false

actual suspend fun signInWithApple(): SocialAuthResult? = null

// Android Google Sign-In would use the Android Google Sign-In library
// (com.google.android.gms.auth.api.signin). Out of scope for this iOS
// pivot — return null/false so the UI hides the button cleanly.
actual fun isGoogleSignInAvailable(): Boolean = false

actual suspend fun signInWithGoogle(): SocialAuthResult? = null
