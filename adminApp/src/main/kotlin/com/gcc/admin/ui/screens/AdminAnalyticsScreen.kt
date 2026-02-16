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
import com.gcc.admin.ui.charts.*
import com.gcc.admin.ui.components.DataColumn
import com.gcc.admin.ui.components.DataTable
import com.gcc.admin.ui.components.StatsCard
import com.gcc.admin.ui.theme.*
import com.gcc.admin.viewmodel.AnalyticsViewModel
import com.gcc.shared.model.WorkerMetrics
import org.koin.compose.koinInject

@Composable
fun AdminAnalyticsScreen() {
    val viewModel = koinInject<AnalyticsViewModel>()
    val stats by viewModel.stats.collectAsState()
    val dailyStats by viewModel.dailyStats.collectAsState()
    val revenueByArea by viewModel.revenueByArea.collectAsState()
    val workerMetrics by viewModel.workerMetrics.collectAsState()
    val sponsorMetrics by viewModel.sponsorMetrics.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AdminTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(AdminTheme.spacing.lg),
    ) {
        // -- Header ---------------------------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Analytics", style = MaterialTheme.typography.headlineMedium)
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }

        error?.let {
            Text(it, color = AdminError, style = MaterialTheme.typography.bodySmall)
        }

        // -- Top Stats Row --------------------------------------------------
        stats?.let { s ->
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
                    value = "$${"%,d".format(s.totalRevenue / 100)}",
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
        }

        // -- Row 1: Daily Jobs LineChart + Revenue by Area BarChart ----------
        Row(
            modifier = Modifier.fillMaxWidth().height(350.dp),
            horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.lg),
        ) {
            // Daily Jobs Line Chart
            Surface(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = AdminTheme.elevation.card,
            ) {
                Column(modifier = Modifier.padding(AdminTheme.spacing.lg)) {
                    Text("Daily Jobs", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(AdminTheme.spacing.md))

                    if (dailyStats.isNotEmpty()) {
                        val jobsSeries = LineChartSeries(
                            label = "Jobs Completed",
                            points = dailyStats.map { it.jobsCompleted.toFloat() },
                            color = ChartGreen,
                        )
                        val labels = dailyStats.mapIndexed { index, stat ->
                            if (index % 5 == 0) stat.date.takeLast(5) else ""
                        }

                        LineChart(
                            series = listOf(jobsSeries),
                            labels = labels,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
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

            // Revenue by Area Bar Chart
            Surface(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = AdminTheme.elevation.card,
            ) {
                Column(modifier = Modifier.padding(AdminTheme.spacing.lg)) {
                    Text("Revenue by Area", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(AdminTheme.spacing.md))

                    if (revenueByArea.isNotEmpty()) {
                        val barColors = listOf(
                            ChartGreen, ChartBlue, ChartGold, ChartTeal,
                            ChartPurple, ChartRed, ChartOrange, ChartPink,
                        )
                        val barData = revenueByArea.mapIndexed { index, area ->
                            BarChartData(
                                label = area.serviceAreaName.take(10),
                                value = (area.totalRevenue / 100f),
                                color = barColors[index % barColors.size],
                            )
                        }

                        BarChart(
                            data = barData,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "No revenue data available",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AdminOnSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // -- Row 2: Top Workers Table + Sponsor Contributions PieChart ------
        Row(
            modifier = Modifier.fillMaxWidth().height(400.dp),
            horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.lg),
        ) {
            // Top Workers DataTable
            Surface(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = AdminTheme.elevation.card,
            ) {
                Column(modifier = Modifier.padding(AdminTheme.spacing.lg)) {
                    Text("Top Workers", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(AdminTheme.spacing.md))

                    val workerColumns = remember {
                        listOf(
                            DataColumn<WorkerMetrics>(
                                header = "Name",
                                weight = 1.5f,
                                content = { metric ->
                                    Text(metric.workerName, style = MaterialTheme.typography.bodyMedium)
                                },
                            ),
                            DataColumn<WorkerMetrics>(
                                header = "Jobs",
                                weight = 0.6f,
                                content = { metric ->
                                    Text(metric.jobsCompleted.toString(), style = MaterialTheme.typography.bodyMedium)
                                },
                            ),
                            DataColumn<WorkerMetrics>(
                                header = "Earnings",
                                weight = 0.8f,
                                content = { metric ->
                                    Text(
                                        text = "$${"%,d".format(metric.totalEarnings / 100)}",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                },
                            ),
                            DataColumn<WorkerMetrics>(
                                header = "Rating",
                                weight = 0.6f,
                                content = { metric ->
                                    Text(
                                        text = metric.averageRating?.let { "%.1f".format(it) } ?: "N/A",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                },
                            ),
                            DataColumn<WorkerMetrics>(
                                header = "Volunteers",
                                weight = 0.7f,
                                content = { metric ->
                                    Text(
                                        text = metric.volunteersCompleted.toString(),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                },
                            ),
                        )
                    }

                    DataTable(
                        items = workerMetrics,
                        columns = workerColumns,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        pageSize = 10,
                    )
                }
            }

            // Sponsor Contributions Pie Chart
            Surface(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = AdminTheme.elevation.card,
            ) {
                Column(modifier = Modifier.padding(AdminTheme.spacing.lg)) {
                    Text("Sponsor Contributions", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(AdminTheme.spacing.md))

                    if (sponsorMetrics.isNotEmpty()) {
                        val pieColors = listOf(
                            ChartGreen, ChartBlue, ChartGold, ChartTeal,
                            ChartPurple, ChartRed, ChartOrange, ChartPink,
                        )
                        val pieData = sponsorMetrics.mapIndexed { index, sponsor ->
                            PieChartData(
                                label = sponsor.sponsorName,
                                value = (sponsor.totalFunded / 100f),
                                color = pieColors[index % pieColors.size],
                            )
                        }

                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            PieChart(
                                data = pieData,
                                donut = true,
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "No sponsor data available",
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
