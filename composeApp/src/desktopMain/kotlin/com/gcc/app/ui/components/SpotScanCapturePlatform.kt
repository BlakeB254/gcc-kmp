package com.gcc.app.ui.components

import java.util.UUID

actual fun getPlatformDeviceModel(): String = "Desktop"

actual fun getPlatformOsVersion(): String =
    System.getProperty("os.name") ?: "Unknown"

actual fun generateSessionId(): String =
    UUID.randomUUID().toString()
