package com.gcc.app.platform

/**
 * Desktop push notification manager — no-op stub.
 * Desktop JVM has no push notification infrastructure.
 */
actual class PushNotificationManager actual constructor() {
    actual suspend fun registerForPushNotifications(): String? = null
    actual fun unregister() {}
}
