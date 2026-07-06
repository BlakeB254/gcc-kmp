package com.gcc.app.model

import com.gcc.shared.model.AdminPayout
import com.gcc.shared.model.MailingAddress
import com.gcc.shared.model.PayoutMethodInfo
import com.gcc.shared.model.WorkerPayoutsResponse
import com.gcc.shared.serialization.GccJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Lives in composeApp commonTest (not shared's, whose cache tests reference a
 * JVM-only sqlite driver and don't currently compile on any configured target).
 *
 * Pins the wire shapes of the ad-hoc payout endpoints
 * (spec: docs/superpowers/specs/2026-07-06-admin-payouts-design.md).
 */
class PayoutModelsTest {

    @Test
    fun payoutMethodInfoDecodesWithNullAddresses() {
        val json = """{"method":"stripe","effectiveMailingAddress":null,"mailingAddressOverride":null}"""
        val info = GccJson.decodeFromString(PayoutMethodInfo.serializer(), json)
        assertEquals("stripe", info.method)
        assertNull(info.effectiveMailingAddress)
    }

    @Test
    fun adminPayoutDecodesCheckQueueRow() {
        // Postgres-style timestamp exercises LenientInstantSerializer, the
        // exact bug class that took down the bounties/points tabs once.
        val json = """
            {
              "id": 3,
              "workerId": 12,
              "workerName": "Ada",
              "workerEmail": "ada@example.com",
              "amountCents": 2500,
              "note": "Community day bonus",
              "method": "check",
              "status": "check_pending",
              "stripeTransferId": null,
              "initiatedByAdminId": 1,
              "mailingAddress": {"name":"Ada","street":"1 Main St","city":"Chicago","state":"IL","zip":"60623"},
              "checkMailedAt": null,
              "createdAt": "2026-07-06 15:35:28+00"
            }
        """.trimIndent()
        val payout = GccJson.decodeFromString(AdminPayout.serializer(), json)
        assertEquals(2500, payout.amountCents)
        assertEquals("check_pending", payout.status)
        assertEquals("1 Main St", payout.mailingAddress?.street)
        assertEquals(2026, payout.createdAt?.toString()?.take(4)?.toInt())
    }

    @Test
    fun workerPayoutsResponseDecodesUnknownStatus() {
        // Statuses are plain strings so an older client survives new ones.
        val json = """{"payouts":[{"id":1,"amountCents":500,"method":"stripe","status":"some_future_status","createdAt":"2026-07-06T12:00:00Z"}]}"""
        val response = GccJson.decodeFromString(WorkerPayoutsResponse.serializer(), json)
        assertEquals("some_future_status", response.payouts.single().status)
    }

    @Test
    fun mailingAddressCompletenessRequiresAllFields() {
        assertTrue(MailingAddress("Ada", "1 Main St", "Chicago", "IL", "60623").isComplete)
        assertFalse(MailingAddress("Ada", "", "Chicago", "IL", "60623").isComplete)
        assertEquals(
            "1 Main St, Chicago, IL 60623",
            MailingAddress("Ada", "1 Main St", "Chicago", "IL", "60623").oneLine(),
        )
    }
}
