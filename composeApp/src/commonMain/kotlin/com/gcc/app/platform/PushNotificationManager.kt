package com.gcc.app.platform

/**
 * Platform-specific push notification registration.
 *
 * - iOS: APNs via UNUserNotificationCenter + UIApplication
 * - Android: FCM via FirebaseMessaging (requires google-services.json)
 * - Desktop: No-op stub
 *
 * Each platform registers for push and retrieves a device token, which
 * is sent to the server via [com.gcc.shared.api.GccApiClient].
 */
expect class PushNotificationManager() {
    /**
     * Request notification permission and register for push notifications.
     * Returns the device token string on success, or null if denied/unavailable.
     */
    suspend fun registerForPushNotifications(): String?

    /**
     * Unregister from push notifications (e.g. on sign out).
     */
    fun unregister()
}
