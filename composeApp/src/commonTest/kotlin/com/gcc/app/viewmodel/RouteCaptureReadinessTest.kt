package com.gcc.app.viewmodel

import com.gcc.shared.cache.CaptureChunkRecord
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RouteCaptureReadinessTest {
    private fun chunk(
        kind: String,
        index: Long = 0,
        uploadedAtMs: Long? = null,
    ) = CaptureChunkRecord(
        sessionId = "env-route-lap",
        chunkIndex = index,
        kind = kind,
        filePath = "/tmp/$kind",
        sizeBytes = 1,
        locationCellId = "1:2",
        r2Key = null,
        uploadedAtMs = uploadedAtMs,
        createdAtMs = 1,
    )

    @Test
    fun completeVideoPoseDepthSetIsReconstructionReadyBeforeUpload() {
        assertTrue(
            listOf(
                chunk("video"),
                chunk("sidecar"),
                chunk("depth"),
            ).hasReconstructionCaptureSet()
        )
    }

    @Test
    fun missingOrPoisonedArtifactKeepsRouteOutOfReconstruction() {
        assertFalse(listOf(chunk("video"), chunk("sidecar")).hasReconstructionCaptureSet())
        assertFalse(
            listOf(
                chunk("video", index = 0),
                chunk("sidecar", index = 1),
                chunk("depth", index = 2),
            ).hasReconstructionCaptureSet()
        )
        assertFalse(
            listOf(
                chunk("video"),
                chunk("sidecar"),
                chunk("depth", uploadedAtMs = -1),
            ).hasReconstructionCaptureSet()
        )
    }
}
