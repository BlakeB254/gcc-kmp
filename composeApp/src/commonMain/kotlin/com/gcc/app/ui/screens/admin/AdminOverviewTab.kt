package com.gcc.app.ui.screens.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
) {
    if (stats == null) return
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { SectionHeader("Platform") }
        item { AdminStatCard("Total Workers", stats.totalWorkers.toString(), "\uD83D\uDC77", onClick = { onNavigateToTab(AdminTab.WORKERS) }) }
        item { AdminStatCard("Verified Workers", stats.verifiedWorkers.toString(), "\u2705", GccGreen, onClick = { onNavigateToTab(AdminTab.WORKERS) }) }
        item { AdminStatCard("Pending Verifications", stats.pendingVerifications.toString(), "\u23F3", GccSky, onClick = { onNavigateToTab(AdminTab.WORKERS) }) }
        item { SectionHeader("Operations") }
        item { AdminStatCard("Active Jobs", stats.activeJobs.toString(), "\u26A1", GccGreen) }
        item { AdminStatCard("Completed Jobs", stats.completedJobs.toString(), "\u2705") }
        item { AdminStatCard("Total Routes", stats.totalRoutes.toString(), "\uD83D\uDDFA", onClick = { onNavigateToTab(AdminTab.ROUTES) }) }
        item { SectionHeader("Spots") }
        item { AdminStatCard("Active Spots", stats.activeSpots.toString(), "\uD83D\uDCCD", GccGreen, onClick = { onNavigateToTab(AdminTab.SPOTS) }) }
        item { AdminStatCard("Total Spots", stats.totalSpots.toString(), "\uD83D\uDDFA", onClick = { onNavigateToTab(AdminTab.SPOTS) }) }
        item { SectionHeader("Bounties") }
        item { AdminStatCard("Active Bounties", stats.activeBounties.toString(), "\uD83C\uDFAF", GccGreen, onClick = { onNavigateToTab(AdminTab.BOUNTIES) }) }
        item { AdminStatCard("Total Bounties", stats.totalBounties.toString(), "\uD83D\uDCB0", onClick = { onNavigateToTab(AdminTab.BOUNTIES) }) }
        item { SectionHeader("Issues") }
        item { AdminStatCard("Open Disputes", stats.openDisputes.toString(), "\u26A0\uFE0F", if (stats.openDisputes > 0) GccError else GccSky) }

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
                AdminStatCard(day.date.takeLast(5), "${day.jobsCompleted} jobs, ${day.newWorkers} new", "\uD83D\uDCC5")
            }
        }

        // ─── Recent Activity ────────────────────────────────────────
        if (recentActivity.isNotEmpty()) {
            item { SectionHeader("Recent Activity") }
            items(recentActivity) { item ->
                val emoji = when (item.type) {
                    AdminActivityType.WORKER_VERIFIED -> "\u2705"
                    AdminActivityType.WORKER_REGISTERED -> "\uD83D\uDC77"
                    AdminActivityType.JOB_COMPLETED -> "\uD83C\uDFC1"
                    AdminActivityType.BOUNTY_POSTED -> "\uD83C\uDFAF"
                    AdminActivityType.BOUNTY_CLAIMED -> "\uD83D\uDCB0"
                    AdminActivityType.BOUNTY_COMPLETED -> "\uD83C\uDF89"
                    AdminActivityType.BLOCK_FUNDED -> "\uD83D\uDC9A"
                    AdminActivityType.SPONSOR_REGISTERED -> "\uD83E\uDD1D"
                    AdminActivityType.ROUTE_PUBLISHED -> "\uD83D\uDDFA"
                    AdminActivityType.DISPUTE_OPENED -> "\u26A0\uFE0F"
                    AdminActivityType.DISPUTE_RESOLVED -> "\u2705"
                }
                AdminStatCard(item.description, item.actorName ?: "", emoji)
            }
        }
    }
}

@Composable
private fun AdminStatCard(
    label: String,
    value: String,
    icon: String,
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
            Text(icon, fontSize = 28.sp)
            Column(modifier = Modifier.weight(1f)) {
                Text(value, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = color)
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onClick != null) {
                Text(
                    "\u203A",
                    fontSize = 24.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        }
    }
}
