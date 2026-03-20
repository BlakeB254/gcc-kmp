package com.gcc.app.platform

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings

private const val TAG = "SecureStorage"
private const val PREFS_FILE = "gcc_secure_prefs"

/**
 * Android secure storage backed by [EncryptedSharedPreferences].
 *
 * Uses AES-256-SIV for key encryption and AES-256-GCM for value encryption,
 * with keys protected by Android Keystore. Context must be initialized via
 * [initContext] before first use (called from [com.gcc.app.MainActivity]).
 */
@Volatile
private var appContext: Context? = null

fun initSecureStorageContext(context: Context) {
    appContext = context.applicationContext
}

actual fun createSecureStorage(): Settings {
    val ctx = appContext
    if (ctx != null) {
        try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            val prefs: SharedPreferences = EncryptedSharedPreferences.create(
                PREFS_FILE,
                masterKeyAlias,
                ctx,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            return SharedPreferencesSettings(prefs)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create EncryptedSharedPreferences, falling back to standard", e)
        }
    } else {
        Log.w(TAG, "Context not initialized, using default Settings")
    }
    return Settings()
}
