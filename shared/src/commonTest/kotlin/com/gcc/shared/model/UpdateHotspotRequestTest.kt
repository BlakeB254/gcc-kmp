package com.gcc.shared.model

import com.gcc.shared.serialization.GccJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateHotspotRequestTest {

    @Test
    fun nullVerificationScoreIsOmittedNotSentAsNull() {
        val json = GccJson.encodeToString(
            UpdateHotspotRequest.serializer(),
            UpdateHotspotRequest(afterPhotoUrl = "https://x/after.jpg", verificationScore = null, status = HotspotStatus.CLEANED),
        )
        assertFalse("verificationScore" in json, json)
        assertTrue("\"afterPhotoUrl\"" in json, json)
    }

    @Test
    fun realVerificationScoreIsSent() {
        val json = GccJson.encodeToString(
            UpdateHotspotRequest.serializer(),
            UpdateHotspotRequest(verificationScore = 0.7f, status = HotspotStatus.CLEANED),
        )
        assertTrue("\"verificationScore\":0.7" in json, json)
        assertEquals(
            0.7f,
            GccJson.decodeFromString(UpdateHotspotRequest.serializer(), json).verificationScore,
        )
    }
}
