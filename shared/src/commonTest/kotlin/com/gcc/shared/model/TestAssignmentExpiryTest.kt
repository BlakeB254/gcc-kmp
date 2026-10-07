package com.gcc.shared.model

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TestAssignmentExpiryTest {
    private val now = Instant.parse("2026-10-07T12:00:00Z").toEpochMilliseconds()

    private fun ago(minutes: Long) = Instant.fromEpochMilliseconds(now - minutes * 60_000L)

    private fun job(
        status: JobStatus,
        payout: Int? = 0,
        claimedAt: Instant? = null,
        startedAt: Instant? = null,
    ) = Job(
        id = 1,
        routeId = 1,
        workerId = 1,
        status = status,
        timing = JobTiming(claimedAt = claimedAt, startedAt = startedAt),
        payoutAmount = payout,
        createdAt = ago(60 * 24 * 90),
        updatedAt = ago(1),
    )

    @Test
    fun constantsMirrorServer() {
        // site/src/lib/stale-test-jobs.ts: TEST_JOB_CLAIM_TTL_MINUTES / TEST_JOB_IN_PROGRESS_TTL_HOURS
        assertTrue(TEST_ASSIGNMENT_START_WINDOW_MINUTES == 60)
        assertTrue(TEST_ASSIGNMENT_IN_PROGRESS_TTL_HOURS == 24)
    }

    @Test
    fun claimedTestJobHidesAfterStartWindow() {
        assertTrue(job(JobStatus.CLAIMED, claimedAt = ago(30)).isVisibleDashboardAssignment(now))
        assertFalse(job(JobStatus.CLAIMED, claimedAt = ago(61)).isVisibleDashboardAssignment(now))
    }

    @Test
    fun inProgressTestJobHidesAfter24Hours() {
        assertTrue(job(JobStatus.IN_PROGRESS, claimedAt = ago(180), startedAt = ago(120)).isVisibleDashboardAssignment(now))
        assertFalse(job(JobStatus.IN_PROGRESS, claimedAt = ago(25 * 60 + 5), startedAt = ago(25 * 60)).isVisibleDashboardAssignment(now))
    }

    @Test
    fun job24ShapeIsHidden() {
        // Production job 24: $0, in_progress since 2026-07-30.
        val job24 = job(
            JobStatus.IN_PROGRESS,
            claimedAt = Instant.parse("2026-07-30T15:00:00Z"),
            startedAt = Instant.parse("2026-07-30T15:05:00Z"),
        )
        assertTrue(job24.isExpiredTestAssignment(now))
        assertFalse(job24.isVisibleDashboardAssignment(now))
    }

    @Test
    fun inProgressWithoutStartStampAgesFromClaim() {
        assertFalse(job(JobStatus.IN_PROGRESS, claimedAt = ago(25 * 60)).isVisibleDashboardAssignment(now))
        assertTrue(job(JobStatus.IN_PROGRESS, claimedAt = ago(60)).isVisibleDashboardAssignment(now))
    }

    @Test
    fun paidJobsNeverExpire() {
        assertTrue(job(JobStatus.CLAIMED, payout = 2500, claimedAt = ago(600)).isVisibleDashboardAssignment(now))
        assertTrue(job(JobStatus.IN_PROGRESS, payout = 2500, startedAt = ago(72 * 60)).isVisibleDashboardAssignment(now))
    }

    @Test
    fun otherStatusesAreNotExpiredByThisRule() {
        assertFalse(job(JobStatus.PENDING_REVIEW, startedAt = ago(72 * 60)).isExpiredTestAssignment(now))
        assertFalse(job(JobStatus.COMPLETED, startedAt = ago(72 * 60)).isExpiredTestAssignment(now))
    }
}
