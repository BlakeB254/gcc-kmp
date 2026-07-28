package com.gcc.app.platform

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class CrashReportRequestTest {

    @Test
    fun requestCarriesCurrentAppVersionForServerSideCrashClustering() {
        val occurredAt = Instant.parse("2026-07-14T20:13:20.678Z")
        val request = crashReportRequest(
            deviceId = "device-123",
            platform = "ios",
            exceptionMessage = "boom",
            stackTrace = "stack",
            occurredAt = occurredAt,
        )

        assertEquals("device-123", request.deviceId)
        assertEquals("ios", request.platform)
        assertEquals(appVersion(), request.appVersion)
        assertEquals("boom", request.exceptionMessage)
        assertEquals("stack", request.stackTrace)
        assertEquals(occurredAt, request.occurredAt)
    }
}
