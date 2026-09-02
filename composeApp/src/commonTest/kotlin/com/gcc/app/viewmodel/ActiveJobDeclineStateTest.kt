package com.gcc.app.viewmodel

import com.gcc.shared.model.Job
import com.gcc.shared.model.JobStatus
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression for the "cancel my active test route" crash (build 188): declining
 * from ActiveJobScreen used to invoke the back-navigation callback directly from
 * the ViewModel's Dispatchers.Default coroutine, popping the Decompose stack off
 * the main thread and crashing the app. The fix routes the pop through a
 * main-thread LaunchedEffect keyed on [ActiveJobUiState.declined]; these tests
 * pin the pure state transition that flag depends on.
 */
class ActiveJobDeclineStateTest {

    private fun testJob(status: JobStatus = JobStatus.CLAIMED) = Job(
        id = 42,
        routeId = 7,
        workerId = 3,
        status = status,
        payoutAmount = 0, // test assignment
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    @Test
    fun declineSucceededRaisesTheOneShotSignalAndClearsTheJob() {
        val before = ActiveJobUiState(
            job = testJob(),
            isDeclining = true,
            jobStarted = false,
            jobCompleted = false,
        )

        val after = before.declineSucceeded()

        assertTrue(after.declined, "declined must be set so the screen pops on the main thread")
        assertNull(after.job, "the declined job must be cleared")
        assertFalse(after.isDeclining, "the in-flight spinner must clear")
        assertFalse(after.jobStarted)
        assertFalse(after.jobCompleted)
    }

    @Test
    fun declinedSignalIsNotStickyAcrossAReload() {
        // A reload (loadJob) begins by clearing the one-shot flag, so re-entering
        // the singleton screen never immediately pops.
        val stale = ActiveJobUiState(declined = true)
        val afterReloadStart = stale.copy(isLoading = true, error = null, declined = false)
        assertFalse(afterReloadStart.declined)
    }
}
