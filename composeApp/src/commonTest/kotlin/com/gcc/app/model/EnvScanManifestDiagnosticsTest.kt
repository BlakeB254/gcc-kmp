package com.gcc.app.model

import com.gcc.shared.model.EnvScanManifest
import com.gcc.shared.serialization.GccJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * Task 16: EnvScanManifest grew a `diagnosticsLog` field carrying the tail of
 * the on-device diagnostics log so scan sessions are debuggable from the DGX
 * pipeline without a screen recording. Additive/optional so manifests
 * recorded before this field existed (and any queued-but-not-yet-uploaded
 * legacy payload in EnvScanSessionUploader's retry queue) still decode.
 */
class EnvScanManifestDiagnosticsTest {

    /** A manifest payload written by a build that predates `diagnosticsLog`. */
    private val legacyJson = """
        {"sessionId":"s1","deviceId":"d1","deviceModel":"Pixel","osVersion":"14",
         "startedAtMs":1000,"completedAtMs":2000,"path":[],"scannedCells":[],
         "scannedCellSizeMeters":3.0,"observedItems":[],"photoKeys":[]}
    """.trimIndent()

    @Test
    fun legacyManifestWithoutDiagnosticsLogStillDecodes() {
        val decoded: EnvScanManifest = GccJson.decodeFromString(legacyJson)
        assertEquals("s1", decoded.sessionId)
        assertNull(decoded.diagnosticsLog)
    }

    @Test
    fun diagnosticsLogRoundTrips() {
        val manifest = GccJson.decodeFromString<EnvScanManifest>(legacyJson)
            .copy(diagnosticsLog = listOf("12:00:00 info [env-capture] session started", "12:00:05 ERR [env-capture] photo upload failed"))
        val reDecoded: EnvScanManifest = GccJson.decodeFromString(GccJson.encodeToString(manifest))
        assertEquals(manifest.diagnosticsLog, reDecoded.diagnosticsLog)
    }
}
