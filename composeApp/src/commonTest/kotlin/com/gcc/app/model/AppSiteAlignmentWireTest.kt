package com.gcc.app.model

import com.gcc.shared.api.DisplayHandleResponse
import com.gcc.shared.api.PendingReview
import com.gcc.shared.api.SpotUpdateResponse
import com.gcc.shared.model.PointsAction
import com.gcc.shared.model.PointsSummary
import com.gcc.shared.model.UpdateReleaseBatchRequest
import com.gcc.shared.model.ApiResponse
import com.gcc.shared.model.Job
import com.gcc.shared.model.JobStatus
import com.gcc.shared.model.MarkMailedResponse
import com.gcc.shared.model.TransactionStatus
import com.gcc.shared.model.VolunteerCleanup
import com.gcc.shared.model.VolunteerCleanupStatus
import com.gcc.shared.model.Worker
import com.gcc.shared.model.isPayoutDenied
import com.gcc.shared.serialization.GccJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Decodes the JSON the site routes actually send (shapes copied from the
 * route code named on each test) into the shared DTOs.
 */
class AppSiteAlignmentWireTest {

    /** site/src/app/api/spots/[id]/volunteer/route.ts — the `?complete` branch. */
    @Test
    fun volunteerCompleteResponseDecodes() {
        val json = """
            {"data":{"id":42,"reportId":42,"workerId":7,
              "beforeMediaUrls":["https://blob.example/spots/7/before.jpg"],
              "afterMediaUrls":["https://blob.example/spots/7/after.jpg"],
              "trashHeapMediaUrl":null,"aiTrashAnalysis":null,
              "locationLat":41.88,"locationLng":-87.63,"locationVerified":true,
              "startedAt":"2026-10-07 09:00:00+00","completedAt":"2026-10-07T10:00:00.000Z",
              "durationMinutes":0,"pdfUrl":null,"status":"completed","verificationNotes":null,
              "pointsAwarded":50,"createdAt":"2026-10-01 10:00:00+00","updatedAt":"2026-10-07T10:00:00.000Z"},
             "success":true,"message":null}
        """.trimIndent()
        val cleanup = GccJson.decodeFromString(ApiResponse.serializer(VolunteerCleanup.serializer()), json).data
        assertEquals(VolunteerCleanupStatus.COMPLETED, cleanup.status)
        assertEquals(50, cleanup.pointsAwarded)
        assertEquals(41.88, cleanup.locationLat)
    }

    /** site/src/lib/mobile-job-response.ts toMobileJob — a denied paid job with evidence. */
    @Test
    fun mobileJobCarriesBothEvidenceListsAndReviewFields() {
        val json = """
            {"id":311,"blockId":10,"routeId":4,"workerId":7,"status":"pending_review",
             "timing":{"claimedAt":"2026-10-07T13:00:00.000Z","startedAt":"2026-10-07T13:10:00.000Z","completedAt":"2026-10-07T15:00:00.000Z","durationMinutes":null},
             "beforePhotos":[{"capturedAt":"2026-10-07T14:02:11.000Z","url":"https://blob.example/spots/7/before.jpg"}],
             "afterPhotos":[{"capturedAt":"2026-10-07T14:02:11.000Z","url":"https://blob.example/spots/7/after.jpg"}],
             "photos":[{"capturedAt":"2026-10-07T14:02:11.000Z","url":"https://blob.example/spots/7/before.jpg"},{"capturedAt":"2026-10-07T14:02:11.000Z","url":"https://blob.example/spots/7/after.jpg"}],
             "gpsPath":null,"payoutAmount":2500,"payoutStatus":"processing",
             "reviewStatus":"denied","approvedByAdminId":null,"deniedByAdminId":1,
             "denyReason":"Photos do not show the block","reviewedAt":"2026-10-07T16:00:00.000Z",
             "rating":null,"reviewNotes":null,"fundingSource":null,"bountyId":null,"sponsorId":null,
             "completionReport":null,"createdAt":"2026-10-07T12:00:00.000Z","updatedAt":"2026-10-07T16:00:00.000Z"}
        """.trimIndent()
        val job = GccJson.decodeFromString(Job.serializer(), json)
        assertEquals(JobStatus.PENDING_REVIEW, job.status)
        assertEquals(1, job.beforePhotos?.size)
        assertEquals(1, job.afterPhotos?.size)
        assertEquals(
            "https://blob.example/spots/7/after.jpg",
            job.afterPhotos!![0].jsonObject["url"]!!.jsonPrimitive.content,
        )
        assertEquals(TransactionStatus.PROCESSING, job.payoutStatus)
        assertTrue(job.isPayoutDenied())
        assertEquals("Photos do not show the block", job.denyReason)
        assertEquals(1, job.deniedByAdminId)
        assertTrue(job.reviewedAt != null)
    }

    /** Every jobs.payout_status value the site writes decodes to a non-null enum. */
    @Test
    fun everyServerPayoutStatusDecodes() {
        for (value in listOf("pending", "processing", "completed", "failed")) {
            val job = GccJson.decodeFromString(
                Job.serializer(),
                """{"id":1,"payoutStatus":"$value","createdAt":"2026-10-07T12:00:00.000Z","updatedAt":"2026-10-07T12:00:00.000Z"}""",
            )
            assertTrue(job.payoutStatus != null, "payoutStatus '$value' decoded to null")
        }
    }

    /** site/src/app/api/admin/jobs/pending-review/route.ts — evidence as URL lists. */
    @Test
    fun pendingReviewQueueDecodes() {
        val json = """
            {"data":[{"jobId":311,"workerId":7,"workerName":"Wendy Worker","payoutAmount":2500,
              "beforePhotos":["https://blob.example/spots/7/before-1.jpg","https://blob.example/spots/7/before-2.jpg"],
              "afterPhotos":["https://blob.example/spots/7/after-1.jpg"],
              "hasScan":false,"scanDepthAvailable":false,"completedAt":"2026-10-07 15:00:00+00"}]}
        """.trimIndent()
        val rows = GccJson.decodeFromString(ApiResponse.serializer(ListSerializer(PendingReview.serializer())), json).data
        assertEquals(2, rows.single().beforePhotos.size)
        assertEquals(listOf("https://blob.example/spots/7/after-1.jpg"), rows.single().afterPhotos)
    }

    /** site/src/lib/worker-mapper.ts — admin role, null city/state, country rank. */
    @Test
    fun mobileWorkerWithoutCityStateDecodes() {
        val json = """
            {"id":1,"name":"Boss","email":"boss@example.org","phone":"","emailVerified":true,
             "address":{"street":"","city":null,"state":null,"zip":"","neighborhood":null,"isNorthLawndale":null},
             "verification":{"status":"pending","governmentIdStatus":"pending","proofOfAddressStatus":"pending","reviewNotes":null},
             "stripe":{"accountId":null,"onboardingStatus":"not_started","instantPayoutsEnabled":false,"defaultPayoutMethod":"standard"},
             "payoutMethod":"stripe","walletBalanceCents":0,
             "stats":{"jobsCompleted":0,"totalEarnings":0,"averageRating":null,"lastJobAt":null,"communityPoints":0},
             "earlyAdopterTier":"standard","twoFactorEnabled":false,"role":"admin",
             "referralCode":null,"displayHandle":null,"memberNumber":0,
             "signupRankings":{"global":120,"country":95,"state":12,"city":4,"neighborhood":2},
             "notifications":{"pushEnabled":false,"smsEnabled":true,"emailEnabled":true},
             "createdAt":"2026-01-01T00:00:00.000Z","updatedAt":"2026-01-02T00:00:00.000Z"}
        """.trimIndent()
        val worker = GccJson.decodeFromString(Worker.serializer(), json)
        assertEquals("admin", worker.role)
        assertNull(worker.address.city)
        assertNull(worker.address.state)
        assertEquals(95, worker.signupRankings.country)
    }

    /** site/src/app/api/admin/payouts/[id]/mark-mailed/route.ts — returns the raw payouts row. */
    @Test
    fun markMailedWorkerCashOutKeepsNullAdmin() {
        val json = """
            {"data":{"payout":{"id":9,"workerId":12,"amountCents":4000,"note":null,"method":"check",
              "status":"check_mailed","stripeTransferId":null,"stripePayoutId":null,
              "initiatedByAdminId":null,"initiatedByWorkerId":12,"idempotencyKey":"wd-12-abc",
              "mailingAddress":{"name":"Ada","street":"1 Main St","city":"Chicago","state":"IL","zip":"60624"},
              "checkMailedAt":"2026-10-07 15:00:00.123+00","checkMailedByAdminId":1,
              "createdAt":"2026-10-06 10:00:00+00","updatedAt":"2026-10-07 15:00:00.123+00"}},
             "success":true,"message":null}
        """.trimIndent()
        val payout = GccJson.decodeFromString(ApiResponse.serializer(MarkMailedResponse.serializer()), json).data.payout!!
        assertNull(payout.initiatedByAdminId)
        assertEquals(12, payout.initiatedByWorkerId)
        assertFalse(payout.checkMailedAt == null)
    }

    /** Every value of enum_points_ledger_action (site/src/db/schema.ts) decodes. */
    @Test
    fun pointsSummaryWithScanActionsDecodes() {
        val actions = listOf(
            "spot_created", "verification_dirty", "verification_clean", "volunteer_cleanup",
            "already_clean_report", "admin_grant", "bug_report", "scan_coverage_new_area",
            "scan_coverage_stale", "scan_coverage_close_upgrade", "scan_coverage_rectification",
            "observation_logged", "civic_capture",
        )
        val entries = actions.mapIndexed { i, a ->
            """{"id":$i,"workerId":7,"action":"$a","points":10,"referenceType":"spot","referenceId":1,"createdAt":"2026-10-07 12:00:00+00"}"""
        }.joinToString(",")
        val summary = GccJson.decodeFromString(
            ApiResponse.serializer(PointsSummary.serializer()),
            """{"data":{"totalPoints":130,"breakdown":{},"recentEntries":[$entries]}}""",
        ).data
        assertEquals(actions.size, summary.recentEntries.size)
        assertEquals(PointsAction.OBSERVATION_LOGGED, summary.recentEntries[11].action)
    }

    /** route-releases PATCH treats a present key as set — unset fields must be omitted. */
    @Test
    fun releaseBatchEditOmitsUnsetFields() {
        val body = GccJson.encodeToString(UpdateReleaseBatchRequest.serializer(), UpdateReleaseBatchRequest(name = "Week 41"))
        assertEquals("""{"name":"Week 41"}""", body)
    }

    /** spots/[id] PATCH and workers/me/handle PATCH response shapes. */
    @Test
    fun spotPatchAndHandleResponsesDecode() {
        val spot = GccJson.decodeFromString(
            ApiResponse.serializer(SpotUpdateResponse.serializer()),
            """{"data":{"id":42,"status":"resolved"}}""",
        ).data
        assertEquals("resolved", spot.status)
        val handle = GccJson.decodeFromString(
            ApiResponse.serializer(DisplayHandleResponse.serializer()),
            """{"data":{"displayHandle":"wendy_w"}}""",
        ).data
        assertEquals("wendy_w", handle.displayHandle)
    }
}
