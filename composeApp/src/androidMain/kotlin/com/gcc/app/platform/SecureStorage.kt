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
actual class SecureStorage actual constructor() : Settings {

    private val delegate: Settings by lazy {
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
                SharedPreferencesSettings(prefs)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create EncryptedSharedPreferences, falling back to standard", e)
                com.russhwolf.settings.Settings()
            }
        } else {
            Log.w(TAG, "Context not initialized, using default Settings")
            com.russhwolf.settings.Settings()
        }
    }

    override val keys: Set<String> get() = delegate.keys
    override val size: Int get() = delegate.size
    override fun clear() = delegate.clear()
    override fun remove(key: String) = delegate.remove(key)
    override fun hasKey(key: String): Boolean = delegate.hasKey(key)
    override fun putInt(key: String, value: Int) = delegate.putInt(key, value)
    override fun getInt(key: String, defaultValue: Int): Int = delegate.getInt(key, defaultValue)
    override fun getIntOrNull(key: String): Int? = delegate.getIntOrNull(key)
    override fun putLong(key: String, value: Long) = delegate.putLong(key, value)
    override fun getLong(key: String, defaultValue: Long): Long = delegate.getLong(key, defaultValue)
    override fun getLongOrNull(key: String): Long? = delegate.getLongOrNull(key)
    override fun putString(key: String, value: String) = delegate.putString(key, value)
    override fun getString(key: String, defaultValue: String): String = delegate.getString(key, defaultValue)
    override fun getStringOrNull(key: String): String? = delegate.getStringOrNull(key)
    override fun putFloat(key: String, value: Float) = delegate.putFloat(key, value)
    override fun getFloat(key: String, defaultValue: Float): Float = delegate.getFloat(key, defaultValue)
    override fun getFloatOrNull(key: String): Float? = delegate.getFloatOrNull(key)
    override fun putDouble(key: String, value: Double) = delegate.putDouble(key, value)
    override fun getDouble(key: String, defaultValue: Double): Double = delegate.getDouble(key, defaultValue)
    override fun getDoubleOrNull(key: String): Double? = delegate.getDoubleOrNull(key)
    override fun putBoolean(key: String, value: Boolean) = delegate.putBoolean(key, value)
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = delegate.getBoolean(key, defaultValue)
    override fun getBooleanOrNull(key: String): Boolean? = delegate.getBooleanOrNull(key)

    companion object {
        @Volatile
        private var appContext: Context? = null

        fun initContext(context: Context) {
            appContext = context.applicationContext
        }
    }
}
