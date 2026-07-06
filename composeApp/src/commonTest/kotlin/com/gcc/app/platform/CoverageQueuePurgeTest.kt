package com.gcc.app.platform

import com.gcc.shared.model.RetractCoverageRequest
import com.gcc.shared.model.ScanCoverageEventRequest
import com.gcc.shared.model.ScanCoverageSubmitResponse
import com.russhwolf.settings.Settings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** In-memory Settings — the interface is tiny; a map-backed fake keeps the test dependency-free. */
private class FakeSettings : Settings {
    private val map = mutableMapOf<String, Any>()
    override val keys: Set<String> get() = map.keys
    override val size: Int get() = map.size
    override fun clear() = map.clear()
    override fun remove(key: String) { map.remove(key) }
    override fun hasKey(key: String): Boolean = key in map
    override fun putInt(key: String, value: Int) { map[key] = value }
    override fun getInt(key: String, defaultValue: Int): Int = map[key] as? Int ?: defaultValue
    override fun getIntOrNull(key: String): Int? = map[key] as? Int
    override fun putLong(key: String, value: Long) { map[key] = value }
    override fun getLong(key: String, defaultValue: Long): Long = map[key] as? Long ?: defaultValue
    override fun getLongOrNull(key: String): Long? = map[key] as? Long
    override fun putString(key: String, value: String) { map[key] = value }
    override fun getString(key: String, defaultValue: String): String = map[key] as? String ?: defaultValue
    override fun getStringOrNull(key: String): String? = map[key] as? String
    override fun putFloat(key: String, value: Float) { map[key] = value }
    override fun getFloat(key: String, defaultValue: Float): Float = map[key] as? Float ?: defaultValue
    override fun getFloatOrNull(key: String): Float? = map[key] as? Float
    override fun putDouble(key: String, value: Double) { map[key] = value }
    override fun getDouble(key: String, defaultValue: Double): Double = map[key] as? Double ?: defaultValue
    override fun getDoubleOrNull(key: String): Double? = map[key] as? Double
    override fun putBoolean(key: String, value: Boolean) { map[key] = value }
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = map[key] as? Boolean ?: defaultValue
    override fun getBooleanOrNull(key: String): Boolean? = map[key] as? Boolean
}

private fun event(capturedAt: String) = ScanCoverageEventRequest(
    deviceId = "dev-1", platform = "ios", lat = 41.86, lng = -87.72,
    distanceMeters = 1.0, scanMode = "spot", deviceTier = "pro_scanner",
    capturedAt = capturedAt,
)

class CoverageQueuePurgeTest {

    @Test
    fun purgeDropsInWindowEventsAndPersists() = runTest {
        val settings = FakeSettings()
        val queue = CoverageEventQueue(submitFn = { ScanCoverageSubmitResponse(inserted = it.size, success = true) }, settings = settings)
        queue.enqueue(event("2026-07-06T15:00:00Z"))
        queue.enqueue(event("2026-07-06T15:00:20Z"))
        val cutoff = kotlinx.datetime.Instant.parse("2026-07-06T15:00:10Z").toEpochMilliseconds()
        val dropped = queue.purgeSince(cutoffEpochMs = cutoff)
        assertEquals(1, dropped)
        // Persistence survives a "restart": a NEW queue on the same settings sees one event.
        var flushedCount = -1
        val reloaded = CoverageEventQueue(submitFn = { flushedCount = it.size; ScanCoverageSubmitResponse(it.size, true) }, settings = settings)
        assertTrue(reloaded.flush())
        assertEquals(1, flushedCount)
    }

    @Test
    fun retractionQueueFlushesAndRetriesOnFailure() = runTest {
        val settings = FakeSettings()
        var attempts = 0
        var succeed = false
        val queue = CoverageRetractionQueue(
            submitFn = { attempts++; succeed },
            settings = settings,
        )
        queue.enqueue(RetractCoverageRequest(deviceId = "dev-1", fromCapturedAt = "2026-07-06T15:00:00Z", toCapturedAt = "2026-07-06T15:00:10Z"))
        assertEquals(false, queue.flush()) // fails — stays queued
        succeed = true
        assertTrue(queue.flush())
        assertEquals(2, attempts)
        assertTrue(queue.flush()) // empty now — no third submit
        assertEquals(2, attempts)
    }
}
