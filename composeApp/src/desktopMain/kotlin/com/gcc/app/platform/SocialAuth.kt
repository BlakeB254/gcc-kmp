package com.gcc.app.platform

actual fun isAppleSignInAvailable(): Boolean = false

actual suspend fun signInWithApple(): SocialAuthResult? = null

actual fun isGoogleSignInAvailable(): Boolean = false

actual suspend fun signInWithGoogle(): SocialAuthResult? = null
