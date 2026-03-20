package com.gcc.app.platform

import com.russhwolf.settings.PreferencesSettings
import com.russhwolf.settings.Settings
import java.util.prefs.Preferences

/**
 * Desktop secure storage — delegates to Java Preferences.
 * Desktop JVM has no hardware-backed keystore equivalent,
 * so this is functionally identical to the default Settings.
 */
actual fun createSecureStorage(): Settings = PreferencesSettings(
    Preferences.userRoot().node("com.globalcommunitycleaners.worker")
)
