package com.gcc.app.platform

import com.gcc.shared.model.DeviceType

actual fun detectDeviceType(): DeviceType {
    return if (detectDeviceCapabilities().deviceClass != DeviceClass.BASIC) {
        DeviceType.AR
    } else {
        DeviceType.GPS
    }
}
