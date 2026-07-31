package com.gcc.backend

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ScanIndexerVerifiedCandidateTest {
    @Test
    fun `candidate bundle keys are not mirrored as published legacy assets`() {
        assertNull(
            ScanIndexer.parseReconstructedKey(
                "reconstructed/1:2/7/verified-candidate/verified.ply"
            )
        )
        assertNull(
            ScanIndexer.parseReconstructedKey(
                "reconstructed/1:2/7/verified-candidate/manifest.json"
            )
        )
        assertNotNull(ScanIndexer.parseReconstructedKey("reconstructed/1:2/7/splat.ply"))
    }
}
