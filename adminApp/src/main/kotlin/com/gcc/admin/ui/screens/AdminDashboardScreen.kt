package com.gcc.admin.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gcc.admin.ui.components.AdminScaffold
import com.gcc.admin.ui.components.StatsCard
import com.gcc.admin.viewmodel.AdminDashboardViewModel
import org.koin.compose.koinInject

/**
 * Main dashboard screen showing key platform metrics.
 *
 * Displays a grid of StatsCards with totals from AdminStatsResponse
 * and a recent activity feed placeholder below.
 */
@Composable
fun AdminDashboardScreen(
    viewModel: AdminDashboardViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()

    AdminScaffold(
        title = "Dashboard",
        subtitle = "Overview",
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
            if (state.isLoading && state.stats == null) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }

            if (state.error != null) {
                Text(
                    text = state.error ?: "",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }

            val stats = state.stats

            // Stats Grid (2 rows of 4)
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
                    title = "Verified Workers",
                    value = (stats?.verifiedWorkers ?: 0).toString(),
                    icon = Icons.Default.VerifiedUser,
                    modifier = Modifier.weight(1f),
                )
                StatsCard(
                    title = "Pending Verifications",
                    value = (stats?.pendingVerifications ?: 0).toString(),
                    icon = Icons.Default.HourglassTop,
                    modifier = Modifier.weight(1f),
                )
                StatsCard(
                    title = "Active Jobs",
                    value = (stats?.activeJobs ?: 0).toString(),
                    icon = Icons.Default.Work,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                StatsCard(
                    title = "Completed Jobs",
                    value = (stats?.completedJobs ?: 0).toString(),
                    icon = Icons.Default.CheckCircle,
                    modifier = Modifier.weight(1f),
                )
                StatsCard(
                    title = "Active Bounties",
                    value = (stats?.activeBounties ?: 0).toString(),
                    icon = Icons.Default.EmojiEvents,
                    modifier = Modifier.weight(1f),
                )
                StatsCard(
                    title = "Open Disputes",
                    value = (stats?.openDisputes ?: 0).toString(),
                    icon = Icons.Default.Gavel,
                    modifier = Modifier.weight(1f),
                )
                StatsCard(
                    title = "Total Revenue",
                    value = "$" + ((stats?.totalRevenue ?: 0) / 100).toString(),
                    icon = Icons.Default.AttachMoney,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Recent Activity Feed
            Text(
                text = "Recent Activity",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(12.dp))

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (state.recentActivity.isEmpty()) {
                        Text(
                            text = "No recent activity",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    } else {
                        state.recentActivity.forEach { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                            ) {
                                Text(
                                    text = item.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = item.actorName ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant,
                                thickness = 0.5.dp,
                            )
                        }
                    }
                }
            }
        }
    }
}
