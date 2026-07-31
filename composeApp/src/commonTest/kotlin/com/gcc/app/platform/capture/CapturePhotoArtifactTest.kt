package com.gcc.app.platform.capture

import com.gcc.shared.cache.CaptureChunkRecord
import kotlin.test.Test
import kotlin.test.assertEquals

class CapturePhotoArtifactTest {
    @Test
    fun confidenceUsesStableBinaryArtifactContract() {
        val record = CaptureChunkRecord(
            sessionId = "env-123-abc",
            chunkIndex = 7,
            kind = "confidence",
            filePath = "/tmp/chunk-007.confidence",
            sizeBytes = 21,
            locationCellId = "464641:-721827",
            r2Key = null,
            uploadedAtMs = null,
            createdAtMs = 1,
        )

        assertEquals("confidence", CaptureChunkUploader.extensionFor(record.kind))
        assertEquals(
            "application/vnd.gcc.depth-confidence-v1",
            CaptureChunkUploader.contentTypeFor("confidence"),
        )
        assertEquals("capture/chunk-7.confidence", CaptureChunkUploader.artifactPathFor(record))
        assertEquals(
            "raw/464641:-721827/env-123-abc/capture/chunk-7.confidence",
            CaptureChunkUploader.expectedR2Key(record),
        )
    }

    @Test
    fun queuedPhotoUsesStableSessionArtifactKey() {
        val record = CaptureChunkRecord(
            sessionId = "env-123-abc",
            chunkIndex = 2,
            kind = "photo",
            filePath = "/tmp/photo.jpg",
            sizeBytes = 42,
            locationCellId = "464641:-721827",
            r2Key = null,
            uploadedAtMs = null,
            createdAtMs = 1,
        )

        assertEquals("jpg", CaptureChunkUploader.extensionFor(record.kind))
        assertEquals("image/jpeg", CaptureChunkUploader.contentTypeFor("jpg"))
        assertEquals("photos/photo-2.jpg", CaptureChunkUploader.artifactPathFor(record))
        assertEquals(
            "raw/464641:-721827/env-123-abc/photos/photo-2.jpg",
            CaptureChunkUploader.expectedR2Key(record),
        )
    }
}
