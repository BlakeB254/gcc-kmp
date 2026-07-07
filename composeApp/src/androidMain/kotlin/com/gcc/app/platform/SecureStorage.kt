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
 * [initSecureStorageContext] before first use (called from
 * [com.gcc.app.MainActivity]); using secure storage before that throws.
 */
@Volatile
private var appContext: Context? = null

fun initSecureStorageContext(context: Context) {
    appContext = context.applicationContext
}

actual fun createSecureStorage(): Settings {
    // Fail closed: silently downgrading to plain (unencrypted, persistent)
    // Settings() would let tokens/credentials land on disk in plaintext.
    // MainActivity.onCreate calls initSecureStorageContext(this) before Koin
    // ever constructs a TokenManager, so a null context here is a wiring bug.
    val ctx = appContext
        ?: throw IllegalStateException(
            "SecureStorage context not initialized — call initSecureStorageContext(context) " +
                "(MainActivity.onCreate) before using secure storage. Refusing to fall back " +
                "to unencrypted SharedPreferences.",
        )
    return try {
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
        // Keystore/keyset corruption: keep the app usable (user just has to
        // log in again) but never persist secrets in plaintext — an in-memory
        // map lives only for this process.
        Log.e(TAG, "Failed to create EncryptedSharedPreferences; using in-memory storage for this process", e)
        InMemorySettings()
    }
}

/**
 * Volatile, process-lifetime [Settings] used only when the encrypted store
 * cannot be created. Nothing is ever written to disk.
 */
private class InMemorySettings : Settings {
    private val map = mutableMapOf<String, Any>()

    override val keys: Set<String> get() = map.keys.toSet()
    override val size: Int get() = map.size

    override fun clear() = map.clear()
    override fun remove(key: String) { map.remove(key) }
    override fun hasKey(key: String): Boolean = map.containsKey(key)

    override fun putInt(key: String, value: Int) { map[key] = value }
    override fun getInt(key: String, defaultValue: Int): Int = getIntOrNull(key) ?: defaultValue
    override fun getIntOrNull(key: String): Int? = map[key] as? Int

    override fun putLong(key: String, value: Long) { map[key] = value }
    override fun getLong(key: String, defaultValue: Long): Long = getLongOrNull(key) ?: defaultValue
    override fun getLongOrNull(key: String): Long? = map[key] as? Long

    override fun putString(key: String, value: String) { map[key] = value }
    override fun getString(key: String, defaultValue: String): String = getStringOrNull(key) ?: defaultValue
    override fun getStringOrNull(key: String): String? = map[key] as? String

    override fun putFloat(key: String, value: Float) { map[key] = value }
    override fun getFloat(key: String, defaultValue: Float): Float = getFloatOrNull(key) ?: defaultValue
    override fun getFloatOrNull(key: String): Float? = map[key] as? Float

    override fun putDouble(key: String, value: Double) { map[key] = value }
    override fun getDouble(key: String, defaultValue: Double): Double = getDoubleOrNull(key) ?: defaultValue
    override fun getDoubleOrNull(key: String): Double? = map[key] as? Double

    override fun putBoolean(key: String, value: Boolean) { map[key] = value }
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = getBooleanOrNull(key) ?: defaultValue
    override fun getBooleanOrNull(key: String): Boolean? = map[key] as? Boolean
}
