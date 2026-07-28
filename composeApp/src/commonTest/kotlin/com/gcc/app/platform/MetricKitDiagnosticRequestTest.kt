package com.gcc.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class MetricKitDiagnosticRequestTest {

    @Test
    fun requestCarriesCurrentAppVersionForServerSideCrashClustering() {
        val request = metricKitDiagnosticRequest(
            deviceId = "device-123",
            platform = "ios",
            fileName = "diagnostic-2026-07-12T23-15-00Z-0.json",
            payload = "{\"crashDiagnostics\":[]}",
        )

        assertEquals("device-123", request.deviceId)
        assertEquals("ios", request.platform)
        assertEquals(appVersion(), request.appVersion)
    }
}
