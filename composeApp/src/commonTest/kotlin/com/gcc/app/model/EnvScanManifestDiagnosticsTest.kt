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

    /**
     * Task 17: EnvScanManifest grew `multiItemPhotoKeys` (last property) —
     * the R2 keys of photos that carried >=2 identified litter reticles at
     * capture time, i.e. the "best shots" for the backend extraction
     * mechanisms. Same additive/optional contract as `diagnosticsLog`.
     */
    @Test
    fun legacyManifestWithoutMultiItemPhotoKeysStillDecodes() {
        val decoded: EnvScanManifest = GccJson.decodeFromString(legacyJson)
        assertEquals("s1", decoded.sessionId)
        assertNull(decoded.multiItemPhotoKeys)
    }

    @Test
    fun multiItemPhotoKeysRoundTrips() {
        val manifest = GccJson.decodeFromString<EnvScanManifest>(legacyJson)
            .copy(multiItemPhotoKeys = listOf("sessions/s1/photo0.jpg", "sessions/s1/photo2.jpg"))
        val reDecoded: EnvScanManifest = GccJson.decodeFromString(GccJson.encodeToString(manifest))
        assertEquals(manifest.multiItemPhotoKeys, reDecoded.multiItemPhotoKeys)
    }
}
