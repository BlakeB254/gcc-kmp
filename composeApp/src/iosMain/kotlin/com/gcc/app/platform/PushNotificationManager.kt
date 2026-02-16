package com.gcc.app.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.UIKit.UIApplication
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.memcpy
import kotlin.coroutines.resume

/**
 * iOS push notification manager using APNs.
 *
 * Requests notification permission via UNUserNotificationCenter, then
 * calls UIApplication.registerForRemoteNotifications(). The device token
 * is received through the AppDelegate callback and stored in the companion
 * object so it can be retrieved after registration.
 */
actual class PushNotificationManager actual constructor() {

    actual suspend fun registerForPushNotifications(): String? {
        val granted = requestPermission()
        if (!granted) return null

        // If we already have a token from a previous registration, return it
        deviceToken?.let { return it }

        // Trigger APNs registration — the token arrives async via AppDelegate
        dispatch_async(dispatch_get_main_queue()) {
            UIApplication.sharedApplication.registerForRemoteNotifications()
        }

        // Wait for the token callback from AppDelegate
        return suspendCancellableCoroutine { cont ->
            pendingTokenCallback = { token ->
                cont.resume(token)
            }
        }
    }

    actual fun unregister() {
        dispatch_async(dispatch_get_main_queue()) {
            UIApplication.sharedApplication.unregisterForRemoteNotifications()
        }
        deviceToken = null
    }

    private suspend fun requestPermission(): Boolean = suspendCancellableCoroutine { cont ->
        val center = UNUserNotificationCenter.currentNotificationCenter()
        val options = UNAuthorizationOptionAlert or UNAuthorizationOptionBadge or UNAuthorizationOptionSound
        center.requestAuthorizationWithOptions(options) { granted, _ ->
            cont.resume(granted)
        }
    }

    companion object {
        var deviceToken: String? = null
            private set

        var pendingTokenCallback: ((String?) -> Unit)? = null

        /**
         * Called from Swift AppDelegate when APNs token is received.
         * Converts the raw token NSData to a hex string.
         */
        @OptIn(ExperimentalForeignApi::class)
        fun didRegisterForRemoteNotifications(deviceTokenData: NSData) {
            val length = deviceTokenData.length.toInt()
            val bytes = ByteArray(length)
            bytes.usePinned { pinned ->
                memcpy(pinned.addressOf(0), deviceTokenData.bytes, deviceTokenData.length)
            }
            val hex = bytes.joinToString("") { byte ->
                (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
            }
            deviceToken = hex
            pendingTokenCallback?.invoke(hex)
            pendingTokenCallback = null
        }

        fun didFailToRegisterForRemoteNotifications() {
            pendingTokenCallback?.invoke(null)
            pendingTokenCallback = null
        }
    }
}
