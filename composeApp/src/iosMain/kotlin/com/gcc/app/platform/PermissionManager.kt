package com.gcc.app.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusDenied
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVAuthorizationStatusRestricted
import platform.AVFoundation.AVCaptureDevice
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLAuthorizationStatusRestricted
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusDenied
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHAuthorizationStatusNotDetermined
import platform.Photos.PHAuthorizationStatusRestricted
import platform.Photos.PHPhotoLibrary
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusDenied
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume

/**
 * iOS PermissionManager — real native implementation using:
 *   Camera:        AVCaptureDevice (AVFoundation)
 *   Location:      CLLocationManager (CoreLocation)
 *   Photos:        PHPhotoLibrary (Photos)
 *   Notifications: UNUserNotificationCenter (UserNotifications)
 */
actual class PermissionManager actual constructor() {

    // ---------------------------------------------------------------------------
    // checkPermission — synchronous status query
    // ---------------------------------------------------------------------------

    @OptIn(ExperimentalForeignApi::class)
    actual fun checkPermission(permission: Permission): PermissionStatus {
        return when (permission) {
            Permission.CAMERA -> mapAvStatus(
                AVCaptureDevice.authorizationStatus
            )
            Permission.LOCATION -> mapClStatus(
                CLLocationManager.authorizationStatus()
            )
            Permission.LOCATION_BACKGROUND -> mapClStatusBackground(
                CLLocationManager.authorizationStatus()
            )
            Permission.NOTIFICATIONS -> {
                // UNUserNotificationCenter status is async-only; return NOT_DETERMINED
                // as a conservative default. Use requestPermission() to get the real status.
                PermissionStatus.NOT_DETERMINED
            }
        }
    }

    // ---------------------------------------------------------------------------
    // requestPermission — suspends until the system dialog is dismissed
    // ---------------------------------------------------------------------------

    @OptIn(ExperimentalForeignApi::class)
    actual suspend fun requestPermission(permission: Permission): PermissionStatus {
        return when (permission) {
            Permission.CAMERA -> requestCameraPermission()
            Permission.LOCATION -> requestLocationPermission(background = false)
            Permission.LOCATION_BACKGROUND -> requestLocationPermission(background = true)
            Permission.NOTIFICATIONS -> requestNotificationPermission()
        }
    }

    // ---------------------------------------------------------------------------
    // isPermanentlyDenied
    // ---------------------------------------------------------------------------

    @OptIn(ExperimentalForeignApi::class)
    actual fun isPermanentlyDenied(permission: Permission): Boolean {
        return when (permission) {
            Permission.CAMERA -> {
                val status = AVCaptureDevice.authorizationStatus
                status == AVAuthorizationStatusDenied || status == AVAuthorizationStatusRestricted
            }
            Permission.LOCATION, Permission.LOCATION_BACKGROUND -> {
                val status = CLLocationManager.authorizationStatus()
                status == kCLAuthorizationStatusDenied || status == kCLAuthorizationStatusRestricted
            }
            Permission.NOTIFICATIONS -> {
                // Notifications can always be re-enabled from Settings, but once
                // the user explicitly denies we treat it as permanently denied.
                false  // Can only be determined asynchronously; callers should use requestPermission()
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Private helpers — Camera
    // ---------------------------------------------------------------------------

    @OptIn(ExperimentalForeignApi::class)
    private suspend fun requestCameraPermission(): PermissionStatus =
        suspendCancellableCoroutine { cont ->
            val current = AVCaptureDevice.authorizationStatus
            if (current != AVAuthorizationStatusNotDetermined) {
                cont.resume(mapAvStatus(current))
                return@suspendCancellableCoroutine
            }
            AVCaptureDevice.requestAccessWithCompletionHandler { granted ->
                cont.resume(
                    if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED_PERMANENTLY
                )
            }
        }

    // ---------------------------------------------------------------------------
    // Private helpers — Location
    // ---------------------------------------------------------------------------

    @OptIn(ExperimentalForeignApi::class)
    private suspend fun requestLocationPermission(background: Boolean): PermissionStatus =
        suspendCancellableCoroutine { cont ->
            val current = CLLocationManager.authorizationStatus()

            // Already determined — no need to show a dialog
            if (current != kCLAuthorizationStatusNotDetermined) {
                val status = if (background) mapClStatusBackground(current) else mapClStatus(current)
                cont.resume(status)
                return@suspendCancellableCoroutine
            }

            // Create a CLLocationManager and a delegate to wait for the callback
            val locationManager = CLLocationManager()

            val delegate = object : NSObject(), CLLocationManagerDelegateProtocol {
                override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
                    val newStatus = CLLocationManager.authorizationStatus()
                    if (newStatus != kCLAuthorizationStatusNotDetermined) {
                        val mapped = if (background) {
                            mapClStatusBackground(newStatus)
                        } else {
                            mapClStatus(newStatus)
                        }
                        // Detach delegate to avoid retaining the manager
                        dispatch_async(dispatch_get_main_queue()) {
                            manager.delegate = null
                        }
                        cont.resume(mapped)
                    }
                }

                // Legacy callback for iOS <14
                @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
                override fun locationManager(
                    manager: CLLocationManager,
                    didChangeAuthorizationStatus: CLAuthorizationStatus,
                ) {
                    if (didChangeAuthorizationStatus != kCLAuthorizationStatusNotDetermined) {
                        val mapped = if (background) {
                            mapClStatusBackground(didChangeAuthorizationStatus)
                        } else {
                            mapClStatus(didChangeAuthorizationStatus)
                        }
                        dispatch_async(dispatch_get_main_queue()) {
                            manager.delegate = null
                        }
                        cont.resume(mapped)
                    }
                }
            }

            dispatch_async(dispatch_get_main_queue()) {
                locationManager.delegate = delegate
                if (background) {
                    locationManager.requestAlwaysAuthorization()
                } else {
                    locationManager.requestWhenInUseAuthorization()
                }
            }

            cont.invokeOnCancellation {
                dispatch_async(dispatch_get_main_queue()) {
                    locationManager.delegate = null
                }
            }
        }

    // ---------------------------------------------------------------------------
    // Private helpers — Notifications
    // ---------------------------------------------------------------------------

    @OptIn(ExperimentalForeignApi::class)
    private suspend fun requestNotificationPermission(): PermissionStatus =
        suspendCancellableCoroutine { cont ->
            val center = UNUserNotificationCenter.currentNotificationCenter()

            // First check current settings synchronously via getNotificationSettings
            center.getNotificationSettingsWithCompletionHandler { settings ->
                val currentStatus = settings?.authorizationStatus ?: UNAuthorizationStatusNotDetermined
                when (currentStatus) {
                    UNAuthorizationStatusAuthorized,
                    UNAuthorizationStatusProvisional,
                    UNAuthorizationStatusEphemeral -> {
                        cont.resume(PermissionStatus.GRANTED)
                        return@getNotificationSettingsWithCompletionHandler
                    }
                    UNAuthorizationStatusDenied -> {
                        cont.resume(PermissionStatus.DENIED_PERMANENTLY)
                        return@getNotificationSettingsWithCompletionHandler
                    }
                    else -> {
                        // NOT_DETERMINED — show the dialog
                        val options = UNAuthorizationOptionAlert or
                            UNAuthorizationOptionBadge or
                            UNAuthorizationOptionSound
                        center.requestAuthorizationWithOptions(options) { granted, _ ->
                            cont.resume(
                                if (granted) PermissionStatus.GRANTED
                                else PermissionStatus.DENIED_PERMANENTLY
                            )
                        }
                    }
                }
            }
        }

    // ---------------------------------------------------------------------------
    // Status mapping helpers
    // ---------------------------------------------------------------------------

    @OptIn(ExperimentalForeignApi::class)
    private fun mapAvStatus(status: Long): PermissionStatus = when (status) {
        AVAuthorizationStatusAuthorized   -> PermissionStatus.GRANTED
        AVAuthorizationStatusDenied       -> PermissionStatus.DENIED_PERMANENTLY
        AVAuthorizationStatusRestricted   -> PermissionStatus.DENIED_PERMANENTLY
        AVAuthorizationStatusNotDetermined -> PermissionStatus.NOT_DETERMINED
        else -> PermissionStatus.NOT_DETERMINED
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun mapClStatus(status: CLAuthorizationStatus): PermissionStatus = when (status) {
        kCLAuthorizationStatusAuthorizedWhenInUse,
        kCLAuthorizationStatusAuthorizedAlways -> PermissionStatus.GRANTED
        kCLAuthorizationStatusDenied          -> PermissionStatus.DENIED_PERMANENTLY
        kCLAuthorizationStatusRestricted      -> PermissionStatus.DENIED_PERMANENTLY
        kCLAuthorizationStatusNotDetermined   -> PermissionStatus.NOT_DETERMINED
        else -> PermissionStatus.NOT_DETERMINED
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun mapClStatusBackground(status: CLAuthorizationStatus): PermissionStatus = when (status) {
        kCLAuthorizationStatusAuthorizedAlways  -> PermissionStatus.GRANTED
        kCLAuthorizationStatusAuthorizedWhenInUse -> PermissionStatus.DENIED  // Has location but not background
        kCLAuthorizationStatusDenied            -> PermissionStatus.DENIED_PERMANENTLY
        kCLAuthorizationStatusRestricted        -> PermissionStatus.DENIED_PERMANENTLY
        kCLAuthorizationStatusNotDetermined     -> PermissionStatus.NOT_DETERMINED
        else -> PermissionStatus.NOT_DETERMINED
    }
}
