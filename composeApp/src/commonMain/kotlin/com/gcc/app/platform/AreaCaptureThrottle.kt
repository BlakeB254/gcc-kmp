package com.gcc.app.platform

import com.gcc.shared.geo.GeofenceVerification

/**
 * Gates raw-frame capture (Task 7) to roughly one pair every
 * [minIntervalMs] AND [minDistanceMeters] of movement, whichever is later --
 * mirrors the existing "continuous Vision + plane detection + depth is
 * already the thermal budget" concern noted elsewhere in the scan code
 * (ArEnvScan.kt): capturing/encoding/uploading a frame is real CPU+battery
 * cost on top of that, so this must not fire every tick. Standing still
 * (e.g. reading the "hold steady" prompt) never re-captures a near-duplicate
 * frame; walking a block captures roughly one pair per couple of meters.
 */
class AreaCaptureThrottle(
    private val minIntervalMs: Long = 2_000,
    private val minDistanceMeters: Double = 1.5,
) {
    private var lastCapturedAtMs: Long = 0
    private var lastLat: Double? = null
    private var lastLng: Double? = null

    fun shouldCapture(nowMs: Long, lat: Double, lng: Double): Boolean {
        if (nowMs - lastCapturedAtMs < minIntervalMs) return false
        val prevLat = lastLat
        val prevLng = lastLng
        if (prevLat != null && prevLng != null) {
            val distance = GeofenceVerification.distanceMeters(prevLat, prevLng, lat, lng)
            if (distance < minDistanceMeters) return false
        }
        return true
    }

    fun markCaptured(nowMs: Long, lat: Double, lng: Double) {
        lastCapturedAtMs = nowMs
        lastLat = lat
        lastLng = lng
    }
}
