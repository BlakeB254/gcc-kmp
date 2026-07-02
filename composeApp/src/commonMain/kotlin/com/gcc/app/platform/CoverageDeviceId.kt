package com.gcc.app.platform

import com.russhwolf.settings.Settings
import kotlin.random.Random

/**
 * Stable per-install device identifier for the scan-coverage ledger
 * (`POST /api/scans/coverage` requires a `deviceId` on every event).
 * Generated once and persisted in the platform secure store, so coverage
 * events from the same install correlate across sessions.
 */
object CoverageDeviceId {

    private const val KEY = "coverage_device_id"

    private val settings: Settings by lazy { createSecureStorage() }

    val value: String by lazy {
        settings.getStringOrNull(KEY) ?: generate().also { settings.putString(KEY, it) }
    }

    private fun generate(): String {
        val hex = "0123456789abcdef"
        return buildString(32) { repeat(32) { append(hex[Random.nextInt(16)]) } }
    }
}

/** DeviceClass -> the coverage ledger's `deviceTier` wire value. */
fun DeviceClass.coverageTier(): String = when (this) {
    DeviceClass.PRO_SCANNER -> "pro_scanner"
    DeviceClass.SCANNER -> "scanner"
    DeviceClass.BASIC -> "basic"
}
