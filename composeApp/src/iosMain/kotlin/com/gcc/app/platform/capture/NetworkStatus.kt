package com.gcc.app.platform.capture

import com.gcc.app.platform.DiagnosticsLog
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Network.nw_interface_type_cellular
import platform.Network.nw_interface_type_wifi
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.Network.nw_path_uses_interface_type
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceBatteryState
import platform.darwin.dispatch_queue_create

/**
 * Network.framework exposes no synchronous "what's the path right now" call
 * — only an async update handler — so this is the "cached monitor" option
 * from the session-capture plan: start ONE `nw_path_monitor` for the process
 * lifetime, lazily on first use, and let its update handler stamp a volatile
 * field. Every [currentNetworkStatus] call after that is then a plain
 * synchronous read, never a fresh async round-trip.
 */
@OptIn(ExperimentalForeignApi::class)
private object CachedPathMonitor {
    @kotlin.concurrent.Volatile
    var status: NetworkStatus = NetworkStatus.UNKNOWN
        private set

    init {
        try {
            val monitor = nw_path_monitor_create()
            val queue = dispatch_queue_create("gcc-network-path-monitor", null)
            nw_path_monitor_set_queue(monitor, queue)
            nw_path_monitor_set_update_handler(monitor) { path ->
                status = when {
                    path == null -> NetworkStatus.UNKNOWN
                    nw_path_get_status(path) != nw_path_status_satisfied -> NetworkStatus.OFFLINE
                    nw_path_uses_interface_type(path, nw_interface_type_wifi) -> NetworkStatus.WIFI
                    nw_path_uses_interface_type(path, nw_interface_type_cellular) -> NetworkStatus.CELLULAR
                    // Wired/other non-cellular interfaces (rare on iPhone):
                    // treat like Wi-Fi for the upload-policy gate.
                    else -> NetworkStatus.WIFI
                }
            }
            nw_path_monitor_start(monitor)
        } catch (e: Throwable) {
            // Never let a monitor-setup failure become a crash — the upload
            // policy just stays conservative (UNKNOWN, cellular-opt-in only)
            // until the next launch retries this.
            DiagnosticsLog.error("network-status", "path monitor init failed", e)
        }
    }
}

actual fun currentNetworkStatus(): NetworkStatus = CachedPathMonitor.status

actual fun isCharging(): Boolean = try {
    val device = UIDevice.currentDevice
    device.batteryMonitoringEnabled = true
    device.batteryState == UIDeviceBatteryState.UIDeviceBatteryStateCharging ||
        device.batteryState == UIDeviceBatteryState.UIDeviceBatteryStateFull
} catch (e: Throwable) {
    DiagnosticsLog.error("network-status", "battery state read failed", e)
    false
}
