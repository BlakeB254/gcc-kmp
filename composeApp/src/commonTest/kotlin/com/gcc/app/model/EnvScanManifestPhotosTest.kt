package com.gcc.app.model

import com.gcc.shared.model.EnvScanManifest
import com.gcc.shared.model.EnvScanPhotoRef
import com.gcc.shared.serialization.GccJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class EnvScanManifestPhotosTest {
    private val legacyJson = """
        {"sessionId":"legacy","deviceId":"d1","deviceModel":"iPhone","osVersion":"19",
         "startedAtMs":1000,"completedAtMs":2000,"path":[],"scannedCells":[],
         "scannedCellSizeMeters":3.0,"observedItems":[],"photoKeys":["raw/cell/photo0.jpg"]}
    """.trimIndent()

    @Test
    fun legacyBarePhotoKeysRemainCompatible() {
        val decoded = GccJson.decodeFromString<EnvScanManifest>(legacyJson)
        assertEquals(listOf("raw/cell/photo0.jpg"), decoded.photoKeys)
        assertNull(decoded.photos)
    }

    @Test
    fun timestampedPhotoReferencesRoundTrip() {
        val manifest = GccJson.decodeFromString<EnvScanManifest>(legacyJson).copy(
            photos = listOf(
                EnvScanPhotoRef(
                    r2Key = "raw/464635:-721803/device/session-photo0.jpg",
                    atMs = 1_785_368_302_743,
                    lat = 41.8590359,
                    lng = -87.7048284,
                ),
            ),
        )
        val decoded = GccJson.decodeFromString<EnvScanManifest>(
            GccJson.encodeToString(manifest),
        )
        assertEquals(manifest.photos, decoded.photos)
    }
}
