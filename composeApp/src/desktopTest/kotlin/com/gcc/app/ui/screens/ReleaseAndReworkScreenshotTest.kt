package com.gcc.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.screens.admin.AdminJobRow
import com.gcc.app.ui.screens.admin.ReleaseJobDialog
import com.gcc.app.ui.screens.admin.isReleasable
import com.gcc.app.ui.theme.GccBackground
import com.gcc.app.ui.theme.GccTheme
import com.gcc.shared.model.AdminJobSummary
import com.gcc.shared.model.JobStatus
import kotlinx.datetime.Instant
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders the admin Release controls and the worker's rework notice to PNG
 * for PR review. Opt-in: runs only when GCC_SCREENSHOT_DIR is set, e.g.
 *   GCC_SCREENSHOT_DIR=/tmp/shots ./gradlew :composeApp:desktopTest --tests '*ReleaseAndReworkScreenshotTest*'
 */
class ReleaseAndReworkScreenshotTest {
    private val outDir = System.getenv("GCC_SCREENSHOT_DIR")?.let(::File)

    private val stalledJob = AdminJobSummary(
        jobId = 412,
        status = JobStatus.IN_PROGRESS,
        workerId = 7,
        workerName = "Dana Worker",
        blockId = 10,
        blockName = "Ogden Ave (S)",
        routeCode = "NL-S01",
        payoutAmount = 2500.0, // cents, as GET /api/admin/jobs sends it
        createdAt = Instant.parse("2026-10-17T08:00:00Z"),
        claimedAt = Instant.parse("2026-10-17T08:00:00Z"),
        startedAt = Instant.parse("2026-10-17T08:30:00Z"),
        stalledAt = Instant.parse("2026-10-18T08:30:00Z"),
    )

    private fun shoot(name: String, height: Int, content: @Composable () -> Unit) {
        val dir = outDir ?: return
        dir.mkdirs()
        val scene = ImageComposeScene(width = 1080, height = height, density = Density(2.5f)) {
            GccTheme {
                Box(Modifier.fillMaxSize().background(GccBackground).padding(16.dp)) { content() }
            }
        }
        try {
            val bytes = scene.render().encodeToData(EncodedImageFormat.PNG)?.bytes
            assertTrue(bytes != null && bytes.isNotEmpty(), "rendered $name")
            File(dir, "$name.png").writeBytes(bytes)
        } finally {
            scene.close()
        }
    }

    @Test
    fun adminRowWithRelease() = shoot("admin-job-row-release", 520) {
        AdminJobRow(job = stalledJob, onReassign = {}, onRelease = {})
    }

    /** Denied under the old rule: PENDING_REVIEW + "denied" — nobody will review it; Release frees the worker. */
    private val legacyDeniedJob = stalledJob.copy(
        jobId = 377,
        status = JobStatus.PENDING_REVIEW,
        stalledAt = null,
        reviewStatus = "denied",
        workerName = "Sam Worker",
    )

    @Test
    fun legacyDeniedIsReleasable() {
        assertTrue(legacyDeniedJob.isReleasable(), "pending_review + denied must offer Release")
        assertTrue(!legacyDeniedJob.copy(reviewStatus = null).isReleasable(), "a job awaiting review must not")
        assertTrue(stalledJob.isReleasable())
    }

    @Test
    fun adminRowLegacyDenied() = shoot("admin-job-row-legacy-denied", 520) {
        AdminJobRow(job = legacyDeniedJob, onReassign = {}, onRelease = {})
    }

    @Test
    fun releaseConfirmation() = shoot("admin-release-confirm", 1200) {
        ReleaseJobDialog(job = stalledJob, inFlight = false, error = null, onConfirm = {}, onDismiss = {})
    }

    @Test
    fun releaseConfirmationWithError() = shoot("admin-release-confirm-error", 1200) {
        ReleaseJobDialog(
            job = stalledJob,
            inFlight = false,
            error = "Job is awaiting review: approve or deny it first",
            onConfirm = {},
            onDismiss = {},
        )
    }

    @Test
    fun workerReworkNotice() = shoot("worker-rework-notice", 480) {
        Column { ReworkNotice(reason = "The alley behind the block was not covered") }
    }
}
