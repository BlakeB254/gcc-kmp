@file:UseSerializers(LenientInstantSerializer::class)

package com.gcc.shared.model

import com.gcc.shared.serialization.LenientInstantSerializer
import kotlinx.serialization.UseSerializers

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray

const val TEST_ASSIGNMENT_START_WINDOW_MINUTES: Int = 60

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
