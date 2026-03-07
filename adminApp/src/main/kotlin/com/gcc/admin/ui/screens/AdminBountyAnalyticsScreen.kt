package com.gcc.admin.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gcc.admin.ui.charts.LineChart
import com.gcc.admin.ui.charts.LineChartSeries
import com.gcc.admin.ui.components.DataColumn
import com.gcc.admin.ui.components.DataTable
import com.gcc.admin.ui.components.StatsCard
import com.gcc.admin.ui.theme.*
import com.gcc.admin.viewmodel.BountyAnalyticsViewModel
import com.gcc.shared.model.BountyLocationStats
import com.gcc.shared.model.BountyPosterStats
import org.koin.compose.koinInject

@Composable
fun AdminBountyAnalyticsScreen() {
    val viewModel = koinInject<BountyAnalyticsViewModel>()
    val stats by viewModel.stats.collectAsState()
    val timeseries by viewModel.timeseries.collectAsState()
    val locationStats by viewModel.locationStats.collectAsState()
    val leaderboard by viewModel.leaderboard.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val timeRange by viewModel.timeRange.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AdminTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(AdminTheme.spacing.lg),
    ) {
        // -- Header with time range chips -----------------------------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Bounty Analytics", style = MaterialTheme.typography.headlineMedium)

            Row(
                horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(7 to "7D", 30 to "30D", 90 to "90D").forEach { (days, label) ->
                    FilterChip(
                        selected = timeRange == days,
                        onClick = { viewModel.setTimeRange(days) },
                        label = { Text(label) },
                    )
                }

                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
        }

        error?.let {
            Text(it, color = AdminError, style = MaterialTheme.typography.bodySmall)
        }

        // -- Stats Cards Row ------------------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.md),
        ) {
            StatsCard(
                title = "Total Bounties",
                value = stats.totalPosted.toString(),
                icon = Icons.Default.EmojiEvents,
                modifier = Modifier.weight(1f),
            )
            StatsCard(
                title = "Total Value",
                value = "$${"%,d".format(stats.totalValueCents / 100)}",
                icon = Icons.Default.AttachMoney,
                modifier = Modifier.weight(1f),
            )
            StatsCard(
                title = "Avg Price",
                value = "$${"%,d".format(stats.avgPriceCents / 100)}",
                icon = Icons.Default.LocalOffer,
                modifier = Modifier.weight(1f),
            )
            StatsCard(
                title = "Completion Rate",
                value = "${"%.1f".format(stats.completionRate)}%",
                icon = Icons.Default.CheckCircle,
                modifier = Modifier.weight(1f),
            )
            StatsCard(
                title = "Spotter Tips Paid",
                value = "$${"%,d".format(stats.totalSpotterTipsCents / 100)}",
                icon = Icons.Default.Visibility,
                modifier = Modifier.weight(1f),
            )
        }

        // -- Timeseries LineChart -------------------------------------------
        Surface(
            modifier = Modifier.fillMaxWidth().height(350.dp),
            shape = MaterialTheme.shapes.medium,
            tonalElevation = AdminTheme.elevation.card,
        ) {
            Column(modifier = Modifier.padding(AdminTheme.spacing.lg)) {
                Text("Bounty Activity", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(AdminTheme.spacing.md))

                if (timeseries.isNotEmpty()) {
                    val postedSeries = LineChartSeries(
                        label = "Posted",
                        points = timeseries.map { it.posted.toFloat() },
                        color = ChartGreen,
                    )
                    val claimedSeries = LineChartSeries(
                        label = "Claimed",
                        points = timeseries.map { it.claimed.toFloat() },
                        color = ChartBlue,
                    )
                    val completedSeries = LineChartSeries(
                        label = "Completed",
                        points = timeseries.map { it.completed.toFloat() },
                        color = ChartGold,
                    )
                    val expiredSeries = LineChartSeries(
                        label = "Expired",
                        points = timeseries.map { it.expired.toFloat() },
                        color = ChartRed,
                    )

                    val labels = timeseries.mapIndexed { index, point ->
                        if (index % 5 == 0) point.date.takeLast(5) else ""
                    }

                    LineChart(
                        series = listOf(postedSeries, claimedSeries, completedSeries, expiredSeries),
                        labels = labels,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )

                    // Legend
                    Spacer(Modifier.height(AdminTheme.spacing.sm))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.lg),
                    ) {
                        listOf(
                            "Posted" to ChartGreen,
                            "Claimed" to ChartBlue,
                            "Completed" to ChartGold,
                            "Expired" to ChartRed,
                        ).forEach { (label, color) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Surface(
                                    modifier = Modifier.size(10.dp),
                                    color = color,
                                    shape = MaterialTheme.shapes.extraSmall,
                                ) {}
                                Text(label, style = MaterialTheme.typography.labelSmall, color = AdminOnSurfaceVariant)
                            }
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "No timeseries data available",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AdminOnSurfaceVariant,
                        )
                    }
                }
            }
        }

        // -- Two-column row: Location + Leaderboard -------------------------
        Row(
            modifier = Modifier.fillMaxWidth().height(400.dp),
            horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.lg),
        ) {
            // Location Breakdown
            Surface(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = AdminTheme.elevation.card,
            ) {
                Column(modifier = Modifier.padding(AdminTheme.spacing.lg)) {
                    Text("Bounties by Location", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(AdminTheme.spacing.md))

                    val locationColumns = remember {
                        listOf(
                            DataColumn<BountyLocationStats>(
                                header = "Area",
                                weight = 1.5f,
                                content = { stat ->
                                    Text(stat.communityAreaName, style = MaterialTheme.typography.bodyMedium)
                                },
                            ),
                            DataColumn<BountyLocationStats>(
                                header = "Count",
                                weight = 0.6f,
                                content = { stat ->
                                    Text(stat.bountyCount.toString(), style = MaterialTheme.typography.bodyMedium)
                                },
                            ),
                            DataColumn<BountyLocationStats>(
                                header = "Value",
                                weight = 0.8f,
                                content = { stat ->
                                    Text(
                                        text = "$${"%,d".format(stat.totalValueCents / 100)}",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                },
                            ),
                            DataColumn<BountyLocationStats>(
                                header = "Completion",
                                weight = 0.8f,
                                content = { stat ->
                                    Text(
                                        text = "${"%.0f".format(stat.completionRate)}%",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                },
                            ),
                        )
                    }

                    DataTable(
                        items = locationStats,
                        columns = locationColumns,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        pageSize = 10,
                    )
                }
            }

            // Poster Leaderboard
            Surface(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = AdminTheme.elevation.card,
            ) {
                Column(modifier = Modifier.padding(AdminTheme.spacing.lg)) {
                    Text("Poster Leaderboard", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(AdminTheme.spacing.md))

                    val leaderboardColumns = remember {
                        listOf(
                            DataColumn<BountyPosterStats>(
                                header = "Name",
                                weight = 1.5f,
                                content = { stat ->
                                    Text(stat.userName, style = MaterialTheme.typography.bodyMedium)
                                },
                            ),
                            DataColumn<BountyPosterStats>(
                                header = "Type",
                                weight = 0.7f,
                                content = { stat ->
                                    Text(stat.posterType.name, style = MaterialTheme.typography.bodyMedium)
                                },
                            ),
                            DataColumn<BountyPosterStats>(
                                header = "Posted",
                                weight = 0.6f,
                                content = { stat ->
                                    Text(stat.bountiesPosted.toString(), style = MaterialTheme.typography.bodyMedium)
                                },
                            ),
                            DataColumn<BountyPosterStats>(
                                header = "Value",
                                weight = 0.8f,
                                content = { stat ->
                                    Text(
                                        text = "$${"%,d".format(stat.totalValueCents / 100)}",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                },
                            ),
                            DataColumn<BountyPosterStats>(
                                header = "Rate",
                                weight = 0.6f,
                                content = { stat ->
                                    Text(
                                        text = "${"%.0f".format(stat.completionRate)}%",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                },
                            ),
                        )
                    }

                    DataTable(
                        items = leaderboard,
                        columns = leaderboardColumns,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        pageSize = 10,
                    )
                }
            }
        }
    }
}
