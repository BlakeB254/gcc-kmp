package com.gcc.app.ui.screens.admin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.components.GccCard
import com.gcc.app.ui.theme.*
import com.gcc.app.viewmodel.AdminJobListViewModel
import com.gcc.shared.model.AdminJobSummary
import com.gcc.shared.model.JobStatus
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.koinInject

/**
 * Chronological, newest-first list of jobs in one status — the drill-in behind
 * the Operations rows on the admin overview.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminJobListScreen(
    title: String,
    status: String?,
    onBack: () -> Unit,
) {
    val viewModel = koinInject<AdminJobListViewModel>()
    val uiState by viewModel.uiState.collectAsState()
    val spacing = GccTheme.spacing

    LaunchedEffect(status) { viewModel.load(status) }

    DisposableEffect(Unit) {
        onDispose { viewModel.onCleared() }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = GccSkyDark,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                ),
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding(),
        ) {
            when {
                uiState.isLoading -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )

                uiState.error != null -> JobListMessage(
                    message = uiState.error ?: "Failed to load jobs",
                    color = GccError,
                    modifier = Modifier.align(Alignment.Center),
                )

                uiState.jobs.isEmpty() -> JobListMessage(
                    message = "No $title right now.",
                    color = TextMuted,
                    modifier = Modifier.align(Alignment.Center),
                )

                else -> LazyColumn(
                    contentPadding = PaddingValues(spacing.md),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    items(uiState.jobs, key = { it.jobId }) { job ->
                        AdminJobRow(job)
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminJobRow(job: AdminJobSummary) {
    val spacing = GccTheme.spacing

    GccCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            JobStatusChip(job.status)
            Spacer(modifier = Modifier.weight(1f))
            job.payoutAmount?.let { amount ->
                Text(
                    formatUsd(amount),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = GccGreen,
                )
            }
        }

        Spacer(modifier = Modifier.height(spacing.sm))

        Text(
            job.workerName ?: "Unassigned",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )

        Spacer(modifier = Modifier.height(spacing.xxs))

        Text(
            jobLocationLabel(job),
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
        )

        Spacer(modifier = Modifier.height(spacing.sm))

        // The lifecycle stamp that matters depends on how far the job has got.
        Text(
            jobTimestampLabel(job),
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun JobStatusChip(status: JobStatus) {
    val color = when (status) {
        JobStatus.AVAILABLE -> GccSky
        JobStatus.CLAIMED -> GccSky
        JobStatus.IN_PROGRESS -> GccGreen
        JobStatus.PENDING_REVIEW -> GccSky
        JobStatus.COMPLETED -> GccGreen
        JobStatus.DISPUTED -> GccError
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.12f),
    ) {
        Text(
            jobStatusLabel(status),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun JobListMessage(message: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        message,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = modifier.padding(24.dp),
    )
}

internal fun jobStatusLabel(status: JobStatus): String = when (status) {
    JobStatus.AVAILABLE -> "Open"
    JobStatus.CLAIMED -> "Claimed"
    JobStatus.IN_PROGRESS -> "In Progress"
    JobStatus.PENDING_REVIEW -> "Pending Review"
    JobStatus.COMPLETED -> "Completed"
    JobStatus.DISPUTED -> "Disputed"
}

private fun jobLocationLabel(job: AdminJobSummary): String {
    val route = job.routeName ?: job.routeCode
    val block = job.blockName ?: "Block ${job.blockId}"
    return if (route != null) "$route · $block" else block
}

// `let` rather than a null-check + smart cast: AdminJobSummary lives in the
// shared module, so Kotlin will not narrow its nullable properties here.
private fun jobTimestampLabel(job: AdminJobSummary): String {
    job.completedAt?.let { return "Completed ${formatTimestamp(it)}" }
    job.startedAt?.let { return "Started ${formatTimestamp(it)}" }
    job.claimedAt?.let { return "Claimed ${formatTimestamp(it)}" }
    return "Created ${formatTimestamp(job.createdAt)}"
}

private fun formatUsd(amount: Double): String {
    val cents = (amount * 100).toLong()
    return "$${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"
}

/** commonMain-safe (no java.text) — kotlinx.datetime plus a month table. */
private fun formatTimestamp(instant: Instant): String {
    val dt = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    val months = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    )
    val month = months.getOrElse(dt.monthNumber - 1) { dt.monthNumber.toString() }
    val hour12 = when {
        dt.hour == 0 -> 12
        dt.hour > 12 -> dt.hour - 12
        else -> dt.hour
    }
    val minute = dt.minute.toString().padStart(2, '0')
    val ampm = if (dt.hour < 12) "AM" else "PM"
    return "$month ${dt.dayOfMonth}, ${dt.year} · $hour12:$minute $ampm"
}
