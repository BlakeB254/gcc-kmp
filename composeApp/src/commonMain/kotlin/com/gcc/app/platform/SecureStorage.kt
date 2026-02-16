package com.gcc.app.platform

import com.russhwolf.settings.Settings

/**
 * Platform-specific secure storage that implements [Settings].
 *
 * - Android: EncryptedSharedPreferences (AES-256, backed by Android Keystore)
 * - iOS: Keychain Services (encrypted by Secure Enclave)
 * - Desktop: Falls back to standard Settings (java.util.prefs)
 *
 * Used as the backing store for [com.gcc.shared.auth.TokenManager] so that
 * JWT tokens are stored securely on mobile devices.
 */
expect class SecureStorage() : Settings
