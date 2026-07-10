package com.gcc.app.ui.screens.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Engineering
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.gcc.app.ui.components.HeatBadge
import com.gcc.app.ui.components.SpotDensityBadge
import com.gcc.app.ui.components.SectionHeader
import com.gcc.app.ui.theme.*
import com.gcc.app.viewmodel.AdminTab
import com.gcc.shared.model.AdminActivityItem
import com.gcc.shared.model.AdminActivityType
import com.gcc.shared.model.AdminDailyStats
import com.gcc.shared.model.AdminStatsResponse

@Composable
internal fun AdminOverviewTab(
    stats: AdminStatsResponse?,
    recentActivity: List<AdminActivityItem> = emptyList(),
    dailyStats: List<AdminDailyStats> = emptyList(),
    onNavigateToTab: (AdminTab) -> Unit = {},
    onNavigateToJobList: (title: String, status: String?) -> Unit = { _, _ -> },
) {
    if (stats == null) return
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { SectionHeader("Platform") }
        item { AdminStatCard("Total Workers", stats.totalWorkers.toString(), Icons.Default.Engineering, onClick = { onNavigateToTab(AdminTab.WORKERS) }) }
        item { AdminStatCard("Verified Workers", stats.verifiedWorkers.toString(), Icons.Default.CheckCircle, GccGreen, onClick = { onNavigateToTab(AdminTab.WORKERS) }) }
        item { AdminStatCard("Pending Verifications", stats.pendingVerifications.toString(), Icons.Default.HourglassEmpty, GccSky, onClick = { onNavigateToTab(AdminTab.WORKERS) }) }
        item { SectionHeader("Operations") }
        // Each row drills into the matching chronological job list.
        // "Jobs in Progress" = workers actively cleaning right now (status = 'in_progress').
        item {
            AdminStatCard("Jobs in Progress", stats.activeJobs.toString(), Icons.Default.Bolt, GccGreen,
                onClick = { onNavigateToJobList("Jobs in Progress", AdminJobStatusFilter.IN_PROGRESS) })
        }
        // Backlog — jobs that exist but haven't been started yet.
        item {
            AdminStatCard("Open Jobs", stats.availableJobs.toString(), Icons.AutoMirrored.Filled.Assignment,
                onClick = { onNavigateToJobList("Open Jobs", AdminJobStatusFilter.AVAILABLE) })
        }
        item {
            AdminStatCard("Claimed (Not Started)", stats.claimedJobs.toString(), Icons.Default.Timer,
                onClick = { onNavigateToJobList("Claimed Jobs", AdminJobStatusFilter.CLAIMED) })
        }
        item {
            AdminStatCard("Completed Jobs", stats.completedJobs.toString(), Icons.Default.CheckCircle,
                onClick = { onNavigateToJobList("Completed Jobs", AdminJobStatusFilter.COMPLETED) })
        }
        item { AdminStatCard("Total Routes", stats.totalRoutes.toString(), Icons.Default.Map, onClick = { onNavigateToTab(AdminTab.ROUTES) }) }
        item { SectionHeader("Spots") }
        item { AdminStatCard("Active Spots", stats.activeSpots.toString(), Icons.Default.LocationOn, GccGreen, onClick = { onNavigateToTab(AdminTab.SPOTS) }) }
        item { AdminStatCard("Total Spots", stats.totalSpots.toString(), Icons.Default.Map, onClick = { onNavigateToTab(AdminTab.SPOTS) }) }
        item { SectionHeader("Bounties") }
        item { AdminStatCard("Active Bounties", stats.activeBounties.toString(), Icons.Default.GpsFixed, GccGreen, onClick = { onNavigateToTab(AdminTab.BOUNTIES) }) }
        item { AdminStatCard("Total Bounties", stats.totalBounties.toString(), Icons.Default.Paid, onClick = { onNavigateToTab(AdminTab.BOUNTIES) }) }
        item { SectionHeader("Issues") }
        item { AdminStatCard("Open Disputes", stats.openDisputes.toString(), Icons.Default.Warning, if (stats.openDisputes > 0) GccError else GccSky) }

        // ─── Cleanliness ────────────────────────────────────────────
        item { SectionHeader("Cleanliness") }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                HeatBadge(label = "Active Spots", score = maxOf(0, 100 - stats.activeSpots * 10))
                SpotDensityBadge(spotCount = stats.activeSpots)
            }
        }

        // ─── Daily Activity (last 7 days) ───────────────────────────
        if (dailyStats.isNotEmpty()) {
            item { SectionHeader("Last 7 Days") }
            items(dailyStats) { day ->
                AdminStatCard(day.date.takeLast(5), "${day.jobsCompleted} jobs, ${day.newWorkers} new", Icons.Default.CalendarToday)
            }
        }

        // ─── Recent Activity ────────────────────────────────────────
        if (recentActivity.isNotEmpty()) {
            item { SectionHeader("Recent Activity") }
            items(recentActivity) { item ->
                val icon: ImageVector = when (item.type) {
                    AdminActivityType.WORKER_VERIFIED -> Icons.Default.CheckCircle
                    AdminActivityType.WORKER_REGISTERED -> Icons.Default.Engineering
                    AdminActivityType.JOB_COMPLETED -> Icons.Default.Flag
                    AdminActivityType.BOUNTY_POSTED -> Icons.Default.GpsFixed
                    AdminActivityType.BOUNTY_CLAIMED -> Icons.Default.Paid
                    AdminActivityType.BOUNTY_COMPLETED -> Icons.Default.EmojiEvents
                    AdminActivityType.BLOCK_FUNDED -> Icons.Default.MonetizationOn
                    AdminActivityType.SPONSOR_REGISTERED -> Icons.Default.Handshake
                    AdminActivityType.ROUTE_PUBLISHED -> Icons.Default.Map
                    AdminActivityType.DISPUTE_OPENED -> Icons.Default.Warning
                    AdminActivityType.DISPUTE_RESOLVED -> Icons.Default.CheckCircle
                }
                AdminStatCard(item.description, item.actorName ?: "", icon)
            }
        }
    }
}

@Composable
private fun AdminStatCard(
    label: String,
    value: String,
    icon: ImageVector,
    color: Color = GccSky,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(28.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(value, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = color)
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onClick != null) {
                Text(
                    "›",
                    fontSize = 24.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        }
    }
}
