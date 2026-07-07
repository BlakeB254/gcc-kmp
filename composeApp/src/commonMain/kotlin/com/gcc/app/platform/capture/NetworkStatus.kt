package com.gcc.app.platform.capture

/**
 * Coarse connectivity read for the capture-chunk upload policy gate (see
 * [CaptureChunkUploader]): Wi-Fi (or charging, or an explicit cellular
 * opt-in) is required before a chunk uploads. Android/Desktop never produce
 * capture chunks (iOS-only, v1 scope guard), so their stubs always report
 * WIFI — there is nothing pending for the policy to gate there anyway.
 */
enum class NetworkStatus { WIFI, CELLULAR, OFFLINE, UNKNOWN }

/** Cheap synchronous read of the current connectivity tier. */
expect fun currentNetworkStatus(): NetworkStatus

/** True while the device is on AC/USB power (upload policy always allows
 *  charging, regardless of network tier). */
expect fun isCharging(): Boolean
