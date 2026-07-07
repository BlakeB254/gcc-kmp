package com.gcc.app.platform.capture

/** Desktop stub — capture (and therefore capture-chunk upload) is iOS-only
 *  in v1; nothing in the queue ever gates on this here. */
actual fun currentNetworkStatus(): NetworkStatus = NetworkStatus.WIFI

actual fun isCharging(): Boolean = false
