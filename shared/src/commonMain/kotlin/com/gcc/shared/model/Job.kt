@file:UseSerializers(LenientInstantSerializer::class)

package com.gcc.shared.model

import com.gcc.shared.serialization.LenientInstantSerializer
import kotlinx.serialization.UseSerializers

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray

/**
 * TTLs for $0 test assignments. Mirrored on the server in
 * `site/src/lib/stale-test-jobs.ts` (`TEST_JOB_CLAIM_TTL_MINUTES`,
 * `TEST_JOB_IN_PROGRESS_TTL_HOURS`) — the route-releases cron releases jobs
 * past these and `GET /api/workers/me/jobs` stops returning them. Change both.
 */
const val TEST_ASSIGNMENT_START_WINDOW_MINUTES: Int = 60
const val TEST_ASSIGNMENT_IN_PROGRESS_TTL_HOURS: Int = 24

@Serializable
data class Job(
    val id: Int,
    val blockId: Int? = null,
    val routeId: Int? = null,
    val workerId: Int? = null,
    val status: JobStatus = JobStatus.AVAILABLE,
    val timing: JobTiming = JobTiming(),
    val photos: JsonArray? = null,
    val gpsPath: JsonArray? = null,
    val payoutAmount: Int? = null,
    val payoutStatus: TransactionStatus? = null,
    val reviewStatus: String? = null,
    val approvedByAdminId: Int? = null,
    val deniedByAdminId: Int? = null,
    val denyReason: String? = null,
    val reviewedAt: Instant? = null,
    val rating: Double? = null,
    val reviewNotes: String? = null,
    val fundingSource: String? = null,
    val bountyId: Int? = null,
    val sponsorId: Int? = null,
    val completionReport: CompletionReport? = null,
    val createdAt: Instant,
    val updatedAt: Instant
)

@Serializable
data class JobTiming(
    val claimedAt: Instant? = null,
    val startedAt: Instant? = null,
    val completedAt: Instant? = null,
    val durationMinutes: Int? = null
)

@Serializable
data class JobEvidenceRequest(
    val beforePhotoUrl: String,
    val afterPhotoUrl: String,
)

fun Job.isTestAssignment(): Boolean =
    payoutAmount == 0

fun Job.testAssignmentStartDeadlineMillis(): Long? =
    if (isTestAssignment()) {
        timing.claimedAt?.toEpochMilliseconds()
            ?.plus(TEST_ASSIGNMENT_START_WINDOW_MINUTES * 60_000L)
    } else {
        null
    }

/**
 * True for a $0 test assignment that has been abandoned: CLAIMED and not
 * started within the start window, or IN_PROGRESS for longer than
 * [TEST_ASSIGNMENT_IN_PROGRESS_TTL_HOURS] (aged from `startedAt`, falling back
 * to `claimedAt`). Paid jobs never expire on the client.
 */
fun Job.isExpiredTestAssignment(nowMillis: Long): Boolean {
    if (!isTestAssignment()) return false
    return when (status) {
        JobStatus.CLAIMED -> {
            if (timing.startedAt != null) return false
            val deadline = testAssignmentStartDeadlineMillis() ?: return false
            deadline <= nowMillis
        }
        JobStatus.IN_PROGRESS -> {
            val started = (timing.startedAt ?: timing.claimedAt)?.toEpochMilliseconds() ?: return false
            nowMillis - started > TEST_ASSIGNMENT_IN_PROGRESS_TTL_HOURS * 3_600_000L
        }
        else -> false
    }
}

/** Whether the dashboard should draw this assignment (map route, guidance line, card). */
fun Job.isVisibleDashboardAssignment(nowMillis: Long): Boolean =
    !isExpiredTestAssignment(nowMillis)
