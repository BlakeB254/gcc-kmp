package com.gcc.shared.offline

import com.russhwolf.settings.Settings

/**
 * In-memory [Settings] implementation backed by a plain [HashMap].
 * Nothing is persisted to disk, SharedPreferences, or NSUserDefaults.
 * Use this for offline/preview modes that need transient storage.
 *
 * Replaces the test-only `MapSettings` from `multiplatform-settings-test`
 * so that production offline modules do not depend on test artifacts.
 */
class MemorySettings : Settings {
    private val map = mutableMapOf<String, Any>()

    override val keys: Set<String> get() = map.keys.toSet()
    override val size: Int get() = map.size

    override fun clear() = map.clear()
    override fun hasKey(key: String): Boolean = map.containsKey(key)
    override fun remove(key: String) { map.remove(key) }

    override fun putInt(key: String, value: Int) { map[key] = value }
    override fun putLong(key: String, value: Long) { map[key] = value }
    override fun putFloat(key: String, value: Float) { map[key] = value }
    override fun putDouble(key: String, value: Double) { map[key] = value }
    override fun putString(key: String, value: String) { map[key] = value }
    override fun putBoolean(key: String, value: Boolean) { map[key] = value }

    override fun getInt(key: String, defaultValue: Int): Int =
        (map[key] as? Int) ?: defaultValue
    override fun getLong(key: String, defaultValue: Long): Long =
        (map[key] as? Long) ?: defaultValue
    override fun getFloat(key: String, defaultValue: Float): Float =
        (map[key] as? Float) ?: defaultValue
    override fun getDouble(key: String, defaultValue: Double): Double =
        (map[key] as? Double) ?: defaultValue
    override fun getString(key: String, defaultValue: String): String =
        (map[key] as? String) ?: defaultValue
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        (map[key] as? Boolean) ?: defaultValue

    override fun getIntOrNull(key: String): Int? = map[key] as? Int
    override fun getLongOrNull(key: String): Long? = map[key] as? Long
    override fun getFloatOrNull(key: String): Float? = map[key] as? Float
    override fun getDoubleOrNull(key: String): Double? = map[key] as? Double
    override fun getStringOrNull(key: String): String? = map[key] as? String
    override fun getBooleanOrNull(key: String): Boolean? = map[key] as? Boolean
}
