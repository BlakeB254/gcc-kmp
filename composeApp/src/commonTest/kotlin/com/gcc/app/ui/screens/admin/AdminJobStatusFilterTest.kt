package com.gcc.app.ui.screens.admin

import com.gcc.shared.model.JobStatus
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Operations rows send these strings to `GET /api/admin/jobs`, which casts
 * them to Postgres's `enum_jobs_status` and answers 400 on a miss. Nothing in
 * the compiler ties the constants to the enum, so pin them to [JobStatus]'s
 * wire format — the same strings the database uses.
 */
class AdminJobStatusFilterTest {

    private fun decode(wireValue: String): JobStatus =
        Json.decodeFromString("\"$wireValue\"")

    @Test
    fun availableFilterMatchesJobStatus() {
        assertEquals(JobStatus.AVAILABLE, decode(AdminJobStatusFilter.AVAILABLE))
    }

    @Test
    fun claimedFilterMatchesJobStatus() {
        assertEquals(JobStatus.CLAIMED, decode(AdminJobStatusFilter.CLAIMED))
    }

    @Test
    fun inProgressFilterMatchesJobStatus() {
        assertEquals(JobStatus.IN_PROGRESS, decode(AdminJobStatusFilter.IN_PROGRESS))
    }

    @Test
    fun completedFilterMatchesJobStatus() {
        assertEquals(JobStatus.COMPLETED, decode(AdminJobStatusFilter.COMPLETED))
    }
}
