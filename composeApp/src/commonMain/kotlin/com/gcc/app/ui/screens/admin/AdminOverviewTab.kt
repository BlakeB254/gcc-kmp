package com.gcc.app.ui.screens.admin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.gcc.app.ui.components.SectionHeader
import com.gcc.app.ui.theme.*
import com.gcc.shared.model.AdminStatsResponse

@Composable
internal fun AdminOverviewTab(stats: AdminStatsResponse?) {
    if (stats == null) return
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { SectionHeader("Platform") }
        item { AdminStatCard("Total Workers", stats.totalWorkers.toString(), "\uD83D\uDC77") }
        item { AdminStatCard("Verified Workers", stats.verifiedWorkers.toString(), "\u2705", GccGreen) }
        item { AdminStatCard("Pending Verifications", stats.pendingVerifications.toString(), "\u23F3", GccGold) }
        item { SectionHeader("Operations") }
        item { AdminStatCard("Active Jobs", stats.activeJobs.toString(), "\u26A1", GccGreen) }
        item { AdminStatCard("Completed Jobs", stats.completedJobs.toString(), "\u2705") }
        item { AdminStatCard("Total Routes", stats.totalRoutes.toString(), "\uD83D\uDDFA") }
        item { SectionHeader("Bounties") }
        item { AdminStatCard("Active Bounties", stats.activeBounties.toString(), "\uD83C\uDFAF", GccGreen) }
        item { AdminStatCard("Total Bounties", stats.totalBounties.toString(), "\uD83D\uDCB0") }
        item { SectionHeader("Issues") }
        item { AdminStatCard("Open Disputes", stats.openDisputes.toString(), "\u26A0\uFE0F", if (stats.openDisputes > 0) GccError else GccGold) }
    }
}

@Composable
private fun AdminStatCard(label: String, value: String, icon: String, color: Color = GccGold) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
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
        }
    }
}
