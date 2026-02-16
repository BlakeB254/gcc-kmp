package com.gcc.admin.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.gcc.admin.ui.charts.LineChart
import com.gcc.admin.ui.charts.LineChartSeries
import com.gcc.admin.ui.components.StatsCard
import com.gcc.admin.ui.theme.*
import com.gcc.admin.viewmodel.AdminDashboardViewModel
import com.gcc.shared.model.AdminActivityItem
import com.gcc.shared.model.AdminActivityType
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.koinInject

@Composable
fun AdminDashboardScreen() {
    val viewModel = koinInject<AdminDashboardViewModel>()
    val stats by viewModel.stats.collectAsState()
    val activity by viewModel.activity.collectAsState()
    val dailyStats by viewModel.dailyStats.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(AdminTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(AdminTheme.spacing.lg),
    ) {
        // ── Header ──────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Dashboard", style = MaterialTheme.typography.headlineMedium)
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }

        error?.let {
            Text(it, color = AdminError, style = MaterialTheme.typography.bodySmall)
        }

        stats?.let { s ->
            // ── Top Stats Row ───────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.md),
            ) {
                StatsCard(
                    title = "Total Workers",
                    value = s.totalWorkers.toString(),
                    icon = Icons.Default.People,
                    modifier = Modifier.weight(1f),
                )
                StatsCard(
                    title = "Active Jobs",
                    value = s.activeJobs.toString(),
                    icon = Icons.Default.Work,
                    modifier = Modifier.weight(1f),
                )
                StatsCard(
                    title = "Total Revenue",
                    value = "$${s.totalRevenue / 100}",
                    icon = Icons.Default.AttachMoney,
                    modifier = Modifier.weight(1f),
                )
                StatsCard(
                    title = "Open Disputes",
                    value = s.openDisputes.toString(),
                    icon = Icons.Default.Gavel,
                    modifier = Modifier.weight(1f),
                )
            }

            // ── Second Stats Row ────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.md),
            ) {
                StatsCard(
                    title = "Verified Workers",
                    value = s.verifiedWorkers.toString(),
                    icon = Icons.Default.VerifiedUser,
                    modifier = Modifier.weight(1f),
                )
                StatsCard(
                    title = "Completed Jobs",
                    value = s.completedJobs.toString(),
                    icon = Icons.Default.CheckCircle,
                    modifier = Modifier.weight(1f),
                )
                StatsCard(
                    title = "Active Bounties",
                    value = s.activeBounties.toString(),
                    icon = Icons.Default.EmojiEvents,
                    modifier = Modifier.weight(1f),
                )
                StatsCard(
                    title = "Pending Verifications",
                    value = s.pendingVerifications.toString(),
                    icon = Icons.Default.HourglassTop,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // ── Charts + Activity ───────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.lg),
        ) {
            // Left: Daily Activity Line Chart
            Surface(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = AdminTheme.elevation.card,
            ) {
                Column(modifier = Modifier.padding(AdminTheme.spacing.lg)) {
                    Text("Daily Activity", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(AdminTheme.spacing.md))

                    if (dailyStats.isNotEmpty()) {
                        val jobsSeries = LineChartSeries(
                            label = "Jobs Completed",
                            points = dailyStats.map { it.jobsCompleted.toFloat() },
                            color = ChartGreen,
                        )
                        val revenueSeries = LineChartSeries(
                            label = "Revenue ($)",
                            points = dailyStats.map { (it.revenue / 100f) },
                            color = ChartBlue,
                        )
                        val labels = dailyStats.mapIndexed { index, stat ->
                            if (index % 5 == 0) stat.date.takeLast(5) else ""
                        }

                        LineChart(
                            series = listOf(jobsSeries, revenueSeries),
                            labels = labels,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )

                        Spacer(Modifier.height(AdminTheme.spacing.sm))

                        // Legend
                        Row(horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.lg)) {
                            LegendItem(color = ChartGreen, label = "Jobs Completed")
                            LegendItem(color = ChartBlue, label = "Revenue ($)")
                        }
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "No daily stats available",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AdminOnSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // Right: Recent Activity
            Surface(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = AdminTheme.elevation.card,
            ) {
                Column(modifier = Modifier.padding(AdminTheme.spacing.lg)) {
                    Text("Recent Activity", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(AdminTheme.spacing.md))

                    if (activity.isNotEmpty()) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(AdminTheme.spacing.xs),
                        ) {
                            items(activity) { item ->
                                ActivityRow(item)
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "No recent activity",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AdminOnSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityRow(item: AdminActivityItem) {
    val (icon, color) = activityIcon(item.type)
    val localTime = item.timestamp.toLocalDateTime(TimeZone.currentSystemDefault())
    val timeText = "${localTime.monthNumber}/${localTime.dayOfMonth} ${localTime.hour}:${localTime.minute.toString().padStart(2, '0')}"

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.sm),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.description,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
            )
        }
        Text(
            text = timeText,
            style = MaterialTheme.typography.labelSmall,
            color = AdminOnSurfaceVariant,
        )
    }
}

@Composable
private fun LegendItem(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Surface(
            modifier = Modifier.size(10.dp),
            shape = MaterialTheme.shapes.extraSmall,
            color = color,
        ) {}
        Text(label, style = MaterialTheme.typography.labelSmall, color = AdminOnSurfaceVariant)
    }
}

private fun activityIcon(type: AdminActivityType): Pair<ImageVector, androidx.compose.ui.graphics.Color> {
    return when (type) {
        AdminActivityType.WORKER_REGISTERED -> Icons.Default.PersonAdd to StatusAvailable
        AdminActivityType.WORKER_VERIFIED -> Icons.Default.VerifiedUser to StatusAvailable
        AdminActivityType.JOB_COMPLETED -> Icons.Default.CheckCircle to StatusCompleted
        AdminActivityType.BOUNTY_POSTED -> Icons.Default.EmojiEvents to StatusWarning
        AdminActivityType.BOUNTY_CLAIMED -> Icons.Default.EmojiEvents to StatusInProgress
        AdminActivityType.BOUNTY_COMPLETED -> Icons.Default.EmojiEvents to StatusCompleted
        AdminActivityType.DISPUTE_OPENED -> Icons.Default.Gavel to StatusError
        AdminActivityType.DISPUTE_RESOLVED -> Icons.Default.Gavel to StatusCompleted
        AdminActivityType.SPONSOR_REGISTERED -> Icons.Default.Business to StatusAvailable
        AdminActivityType.BLOCK_FUNDED -> Icons.Default.AttachMoney to StatusInProgress
        AdminActivityType.ROUTE_PUBLISHED -> Icons.Default.Map to StatusAvailable
    }
}
