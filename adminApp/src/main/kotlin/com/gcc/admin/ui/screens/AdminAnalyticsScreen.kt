package com.gcc.admin.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gcc.admin.ui.charts.BarChart
import com.gcc.admin.ui.charts.ChartDataPoint
import com.gcc.admin.ui.charts.LineChart
import com.gcc.admin.ui.components.*
import com.gcc.admin.ui.theme.*
import com.gcc.admin.viewmodel.AnalyticsViewModel
import org.koin.compose.koinInject

/**
 * Analytics screen with charts and metrics tables.
 *
 * Displays StatsCards, a BarChart for revenue by service area,
 * a LineChart placeholder for trends, and tables for worker/sponsor metrics.
 */
@Composable
fun AdminAnalyticsScreen(
    viewModel: AnalyticsViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()

    AdminScaffold(
        title = "Analytics",
        subtitle = "Operations",
        actions = {
            IconButton(onClick = { viewModel.refresh() }) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Refresh",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            if (state.error != null) {
                Text(
                    text = state.error ?: "",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }

            val stats = state.stats

            // Stats row
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                StatsCard(
                    title = "Total Workers",
                    value = (stats?.totalWorkers ?: 0).toString(),
                    icon = Icons.Default.People,
                    modifier = Modifier.weight(1f),
                )
                StatsCard(
                    title = "Total Jobs",
                    value = (stats?.totalJobs ?: 0).toString(),
                    icon = Icons.Default.Work,
                    modifier = Modifier.weight(1f),
                )
                StatsCard(
                    title = "Total Revenue",
                    value = "$" + ((stats?.totalRevenue ?: 0) / 100).toString(),
                    icon = Icons.Default.AttachMoney,
                    modifier = Modifier.weight(1f),
                )
                StatsCard(
                    title = "Total Sponsors",
                    value = (stats?.totalSponsors ?: 0).toString(),
                    icon = Icons.Default.Business,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Charts row
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Revenue by Area chart
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "Revenue by Service Area",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(bottom = 16.dp),
                        )
                        if (state.revenueByArea.isNotEmpty()) {
                            BarChart(
                                data = state.revenueByArea.map { area ->
                                    ChartDataPoint(
                                        label = area.serviceAreaName,
                                        value = (area.totalRevenue / 100).toFloat(),
                                    )
                                },
                            )
                        } else {
                            Text(
                                text = "No revenue data available",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 24.dp),
                            )
                        }
                    }
                }

                // Trend chart placeholder
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "Daily Trend (Placeholder)",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(bottom = 16.dp),
                        )
                        LineChart(
                            data = listOf(
                                ChartDataPoint("Mon", 12f),
                                ChartDataPoint("Tue", 19f),
                                ChartDataPoint("Wed", 15f),
                                ChartDataPoint("Thu", 25f),
                                ChartDataPoint("Fri", 22f),
                                ChartDataPoint("Sat", 30f),
                                ChartDataPoint("Sun", 18f),
                            ),
                            lineColor = ChartBlue,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Worker Metrics Table
            if (state.workerMetrics.isNotEmpty()) {
                Text(
                    text = "Top Worker Metrics",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                DataTable(
                    items = state.workerMetrics,
                    columns = listOf(
                        TableColumn(header = "Worker", weight = 1.5f) { m ->
                            Text(
                                text = m.workerName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        TableColumn(header = "Jobs", weight = 0.8f) { m ->
                            Text(
                                text = m.jobsCompleted.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        TableColumn(header = "Earnings", weight = 1f) { m ->
                            Text(
                                text = "$" + (m.totalEarnings / 100).toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                        TableColumn(header = "Rating", weight = 0.6f) { m ->
                            Text(
                                text = m.averageRating?.toString() ?: "N/A",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        TableColumn(header = "Badges", weight = 0.6f) { m ->
                            Text(
                                text = m.badgesEarned.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    ),
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Sponsor Metrics Table
            if (state.sponsorMetrics.isNotEmpty()) {
                Text(
                    text = "Sponsor Metrics",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                DataTable(
                    items = state.sponsorMetrics,
                    columns = listOf(
                        TableColumn(header = "Sponsor", weight = 1.5f) { m ->
                            Text(
                                text = m.sponsorName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        TableColumn(header = "Funded", weight = 1f) { m ->
                            Text(
                                text = "$" + (m.totalFunded / 100).toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                        TableColumn(header = "Blocks", weight = 0.6f) { m ->
                            Text(
                                text = m.blocksSponsored.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        TableColumn(header = "Bounties", weight = 0.6f) { m ->
                            Text(
                                text = m.bountiesPosted.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        TableColumn(header = "Balance", weight = 1f) { m ->
                            Text(
                                text = "$" + (m.walletBalance / 100).toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        },
                    ),
                )
            }
        }
    }
}
