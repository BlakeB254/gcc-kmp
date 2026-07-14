package com.gcc.app.model

import com.gcc.shared.model.CaptureHealth
import com.gcc.shared.model.EnvScanManifest
import com.gcc.shared.serialization.GccJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * Splat-reliability spec, Task 5: EnvScanManifest grew a `captureHealth`
 * block — a session-level capture-quality summary (video coverage seconds,
 * max thermal state, degraded/dropped chunk counts, writer errors) so the
 * backend can flag splat-degraded sessions at index time without parsing
 * the pose sidecar. Additive/optional, same contract as `eventLogKey`:
 * manifests recorded before this field existed must still decode.
 */
class EnvScanManifestCaptureHealthTest {

    /** A manifest payload written by a build that predates captureHealth. */
    private val legacyJson = """
        {"sessionId":"s1","deviceId":"d1","deviceModel":"Pixel","osVersion":"14",
         "startedAtMs":1000,"completedAtMs":2000,"path":[],"scannedCells":[],
         "scannedCellSizeMeters":3.0,"observedItems":[],"photoKeys":[]}
    """.trimIndent()

    @Test
    fun legacyManifestWithoutCaptureHealthStillDecodes() {
        val decoded: EnvScanManifest = GccJson.decodeFromString(legacyJson)
        assertEquals("s1", decoded.sessionId)
        assertNull(decoded.captureHealth)
    }

    @Test
    fun captureHealthRoundTrips() {
        val manifest = GccJson.decodeFromString<EnvScanManifest>(legacyJson).copy(
            captureHealth = CaptureHealth(
                sessionSeconds = 311.2,
                videoCoveredSeconds = 44.7,
                maxThermalState = "serious",
                degradedChunkCount = 1,
                droppedVideoChunkCount = 1,
                writerErrorCount = 0,
            ),
        )
        val reDecoded: EnvScanManifest = GccJson.decodeFromString(GccJson.encodeToString(manifest))
        assertEquals(manifest.captureHealth, reDecoded.captureHealth)
    }
}
