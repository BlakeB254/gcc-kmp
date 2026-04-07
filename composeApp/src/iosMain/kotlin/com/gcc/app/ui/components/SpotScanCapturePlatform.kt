package com.gcc.app.ui.components

import platform.UIKit.UIDevice
import platform.Foundation.NSUUID

actual fun getPlatformDeviceModel(): String =
    UIDevice.currentDevice.model

actual fun getPlatformOsVersion(): String =
    "iOS ${UIDevice.currentDevice.systemVersion}"

actual fun generateSessionId(): String =
    NSUUID().UUIDString
