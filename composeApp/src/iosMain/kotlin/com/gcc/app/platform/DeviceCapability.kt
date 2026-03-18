package com.gcc.app.platform

import com.gcc.shared.model.DeviceType

actual fun detectDeviceType(): DeviceType {
    return if (hasLiDAR()) DeviceType.AR else DeviceType.GPS
}
