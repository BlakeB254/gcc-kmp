package com.gcc.app.model

import com.gcc.shared.model.AdminRoutePublishRequest
import com.gcc.shared.model.ClaimStatus
import com.gcc.shared.serialization.GccJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression for the "publish routes as not-claimable" UI error (build 188).
 *
 * GccJson has encodeDefaults=true, so before the @EncodeDefault(NEVER) fix a
 * plain publish request serialized as `{"claimStatus":null,...}`. The publish
 * endpoint treats a present-but-null claimStatus as a supplied value and
 * returns HTTP 400, so every "publish this route" tap failed. The wire form
 * must omit unset fields entirely.
 */
class AdminRoutePublishRequestWireTest {

    @Test
    fun plainPublishOmitsClaimStatus() {
        val json = GccJson.encodeToString(AdminRoutePublishRequest())
        assertFalse(
            "claimStatus" in json,
            "an unset claimStatus must be omitted, not sent as null (got: $json)",
        )
        assertFalse("publishAt" in json, "unset publishAt must be omitted (got: $json)")
        assertFalse("unpublishAt" in json, "unset unpublishAt must be omitted (got: $json)")
        assertEquals("{}", json)
    }

    @Test
    fun explicitClaimStatusIsStillSent() {
        val json = GccJson.encodeToString(
            AdminRoutePublishRequest(claimStatus = ClaimStatus.CLOSED),
        )
        assertTrue("claimStatus" in json, "an explicit claimStatus must be serialized (got: $json)")
        assertTrue("closed" in json, "closed must serialize to its wire value (got: $json)")
    }
}
