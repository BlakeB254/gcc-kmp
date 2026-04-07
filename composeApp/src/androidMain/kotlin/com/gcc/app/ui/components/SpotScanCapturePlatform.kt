package com.gcc.app.ui.components

import android.os.Build
import java.util.UUID

actual fun getPlatformDeviceModel(): String =
    "${Build.MANUFACTURER} ${Build.MODEL}"

actual fun getPlatformOsVersion(): String =
    "Android ${Build.VERSION.RELEASE}"

actual fun generateSessionId(): String =
    UUID.randomUUID().toString()
