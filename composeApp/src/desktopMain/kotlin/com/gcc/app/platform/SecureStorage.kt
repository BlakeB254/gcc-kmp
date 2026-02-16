package com.gcc.app.platform

import com.russhwolf.settings.PreferencesSettings
import com.russhwolf.settings.Settings
import java.util.prefs.Preferences

/**
 * Desktop secure storage — delegates to Java Preferences.
 * Desktop JVM has no hardware-backed keystore equivalent,
 * so this is functionally identical to the default Settings.
 */
actual class SecureStorage actual constructor() : Settings {
    private val delegate: Settings = PreferencesSettings(
        Preferences.userRoot().node("com.globalcommunitycleaners.worker")
    )

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
}
