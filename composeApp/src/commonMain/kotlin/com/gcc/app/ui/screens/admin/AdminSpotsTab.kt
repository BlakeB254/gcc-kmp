package com.gcc.app.ui.screens.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gcc.app.ui.theme.*
import com.gcc.shared.model.CommunityReport
import com.gcc.shared.model.CommunityReportStatus
import com.gcc.shared.model.CommunityReportType
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
internal fun AdminSpotsTab(
    spotReports: List<CommunityReport>,
    error: String?,
    onSpotClick: (Int) -> Unit,
    onCreateClick: () -> Unit,
    onDismissError: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredReports = remember(spotReports, searchQuery) {
        if (searchQuery.isBlank()) spotReports
        else {
            val q = searchQuery.lowercase()
            spotReports.filter {
                (it.reporterName?.lowercase()?.contains(q) == true) ||
                (it.description?.lowercase()?.contains(q) == true) ||
                it.tags.any { tag -> tag.name.lowercase().contains(q) }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                AdminSearchBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    placeholder = "Search spots...",
                )
            }
            // Error banner
            if (error != null) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = GccError.copy(alpha = 0.1f),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                error,
                                style = MaterialTheme.typography.bodySmall,
                                color = GccError,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onDismissError) {
                                Text("Dismiss", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    if (searchQuery.isBlank()) "${spotReports.size} spot reports"
                    else "${filteredReports.size} of ${spotReports.size} spot reports",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                )
            }
            if (filteredReports.isEmpty() && searchQuery.isNotBlank()) {
                item {
                    Box(
                        Modifier.fillMaxWidth().height(120.dp).padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "No spots matching \"$searchQuery\"",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(filteredReports, key = { it.id }) { report ->
                val reporterDisplay = if (report.reporterName == "GCC") "GCC" else (report.reporterName ?: "Anonymous")
                val descSnippet = report.description?.take(60)?.let {
                    if ((report.description?.length ?: 0) > 60) "$it..." else it
                } ?: "No description"
                val mediaCount = report.mediaUrls.size
                val statusColor = when (report.status) {
                    CommunityReportStatus.PENDING -> GccGold
                    CommunityReportStatus.VERIFIED -> GccGreen
                    CommunityReportStatus.RESOLVED -> GccGold
                    CommunityReportStatus.ACKNOWLEDGED -> GccBlue
                    CommunityReportStatus.REJECTED -> GccError
                }
                val localDt = report.createdAt.toLocalDateTime(TimeZone.currentSystemDefault())
                val dateStr = "${localDt.monthNumber}/${localDt.dayOfMonth}/${localDt.year}"
                val hour = localDt.hour % 12
                val amPm = if (localDt.hour < 12) "AM" else "PM"
                val timeStr = "${if (hour == 0) 12 else hour}:${localDt.minute.toString().padStart(2, '0')} $amPm"

                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable { onSpotClick(report.id) },
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val typeIcon = if (report.type == CommunityReportType.SIGHTING) "\uD83D\uDC41" else "\uD83E\uDDF9"
                                Text(
                                    "$typeIcon $reporterDisplay",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = if (report.reporterName == "GCC") GccGreen else MaterialTheme.colorScheme.onSurface,
                                )
                                if (mediaCount > 0) {
                                    Text(
                                        " \u2022 $mediaCount photo${if (mediaCount != 1) "s" else ""}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Text(
                                "$dateStr at $timeStr",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (report.tags.isNotEmpty()) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.padding(vertical = 2.dp),
                                ) {
                                    report.tags.take(4).forEach { tag ->
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = GccGold.copy(alpha = 0.1f),
                                        ) {
                                            Text(
                                                tag.name.lowercase(),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = GccGold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            )
                                        }
                                    }
                                }
                            }
                            Text(
                                descSnippet,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = statusColor.copy(alpha = 0.15f),
                        ) {
                            Text(
                                report.status.name.lowercase(),
                                style = MaterialTheme.typography.labelMedium,
                                color = statusColor,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }

        // FAB
        FloatingActionButton(
            onClick = onCreateClick,
            containerColor = GccGreen,
            contentColor = Color.White,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            Text("+", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
    }
}
