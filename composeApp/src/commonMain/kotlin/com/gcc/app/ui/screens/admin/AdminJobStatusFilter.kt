package com.gcc.app.ui.screens.admin

/**
 * The `status` values the Operations rows send to `GET /api/admin/jobs`.
 *
 * These must stay byte-equal to Postgres's `enum_jobs_status`, which is also
 * what [com.gcc.shared.model.JobStatus] declares via `@SerialName`. The server
 * answers 400 for any other value, so drift here is a runtime failure, not a
 * compile error — `AdminJobStatusFilterTest` pins them against the enum.
 */
internal object AdminJobStatusFilter {
    const val AVAILABLE = "available"
    const val CLAIMED = "claimed"
    const val IN_PROGRESS = "in_progress"
    const val COMPLETED = "completed"
}
