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
                        AdminJobRow(
                            job = job,
                            onReassign = if (job.workerId != null) {
                                { viewModel.openReassign(job) }
                            } else null,
                        )
                    }
                }
            }
        }
    }

    uiState.reassignJob?.let { job ->
        ReassignWorkerDialog(
            job = job,
            uiState = uiState,
            onSearchChange = viewModel::updateReassignSearch,
            onPick = viewModel::reassign,
            onDismiss = viewModel::dismissReassign,
        )
    }

    uiState.reassignSuccess?.let { message ->
        AlertDialog(
            onDismissRequest = { viewModel.clearReassignSuccess() },
            title = { Text("Job reassigned") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearReassignSuccess() }) { Text("OK") }
            },
        )
    }
}

/**
 * Pick the worker who really did this job. Reassigning a completed job also
 * moves the wallet credit (and stats) from the old worker to the new one.
 */
@Composable
private fun ReassignWorkerDialog(
    job: AdminJobSummary,
    uiState: com.gcc.app.viewmodel.AdminJobListUiState,
    onSearchChange: (String) -> Unit,
    onPick: (com.gcc.shared.model.Worker) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!uiState.reassignInFlight) onDismiss() },
        title = { Text("Reassign job #${job.jobId}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Currently credited to ${job.workerName ?: "unassigned"}. " +
                        "Pick who really did the work — any payout already in the old " +
                        "worker's wallet moves to the new worker.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                )
                OutlinedTextField(
                    value = uiState.reassignSearch,
                    onValueChange = onSearchChange,
                    label = { Text("Search workers") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (uiState.reassignSearching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp).align(Alignment.CenterHorizontally),
                        strokeWidth = 2.dp,
                    )
                }
                uiState.reassignError?.let { err ->
                    Text(err, color = GccError, style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn(
                    modifier = Modifier.heightIn(max = 260.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(
                        uiState.reassignCandidates.filter { it.id != job.workerId },
                        key = { it.id },
                    ) { worker ->
                        TextButton(
                            onClick = { onPick(worker) },
                            enabled = !uiState.reassignInFlight,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(worker.name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    worker.email,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !uiState.reassignInFlight) { Text("Cancel") }
        },
    )
}

@Composable
private fun AdminJobRow(
    job: AdminJobSummary,
    onReassign: (() -> Unit)? = null,
) {
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                job.workerName ?: "Unassigned",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (onReassign != null) {
                TextButton(onClick = onReassign) { Text("Reassign") }
            }
        }

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

// payoutAmount arrives in CENTS (jobs.payout_amount, e.g. 10000 = $100). The
// old implementation multiplied by 100 again and showed $100 routes as
// "$10000.00" in the completed-jobs list.
private fun formatUsd(amountCents: Double): String =
    com.gcc.shared.util.GccFormatters.formatCentsLong(amountCents.toLong())

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
