package com.gcc.admin.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gcc.admin.ui.components.DetailRow
import com.gcc.admin.ui.components.StatsCard
import com.gcc.admin.ui.theme.*
import com.gcc.admin.viewmodel.SponsorManagementViewModel
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.koinInject

@Composable
fun AdminSponsorDetailScreen(sponsorId: Int, onBack: () -> Unit) {
    val viewModel = koinInject<SponsorManagementViewModel>()
    val sponsors by viewModel.sponsors.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    val sponsor = sponsors.find { it.id == sponsorId }

    Column(
        modifier = Modifier.fillMaxSize().padding(AdminTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(AdminTheme.spacing.lg),
    ) {
        // -- Header with back button ----------------------------------------
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.sm),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Text("Sponsor Detail", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }

        error?.let {
            Text(it, color = AdminError, style = MaterialTheme.typography.bodySmall)
        }

        if (sponsor == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Sponsor not found",
                    style = MaterialTheme.typography.bodyLarge,
                    color = AdminOnSurfaceVariant,
                )
            }
            return
        }

        // -- Info Section ---------------------------------------------------
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            tonalElevation = AdminTheme.elevation.card,
        ) {
            Column(
                modifier = Modifier.padding(AdminTheme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(AdminTheme.spacing.sm),
            ) {
                Text("Information", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(AdminTheme.spacing.xs))
                DetailRow("Name", sponsor.name)
                DetailRow("Email", sponsor.email)
                DetailRow("Phone", sponsor.phone ?: "N/A")
                DetailRow("Type", sponsor.type.name.lowercase().replaceFirstChar { it.uppercase() })

                sponsor.business?.let { biz ->
                    biz.name?.let { DetailRow("Business", it) }
                    biz.website?.let { DetailRow("Website", it) }
                }
            }
        }

        // -- Stats Row ------------------------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.md),
        ) {
            StatsCard(
                title = "Total Donated",
                value = "$${"%,d".format(sponsor.stats.totalDonated / 100)}",
                icon = Icons.Default.AttachMoney,
                modifier = Modifier.weight(1f),
            )
            StatsCard(
                title = "Blocks Sponsored",
                value = sponsor.stats.blocksSponsored.toString(),
                icon = Icons.Default.GridView,
                modifier = Modifier.weight(1f),
            )
            StatsCard(
                title = "Frequency",
                value = sponsor.preferences.frequency.name.lowercase()
                    .replace("_", " ")
                    .replaceFirstChar { it.uppercase() },
                icon = Icons.Default.Refresh,
                modifier = Modifier.weight(1f),
            )
        }

        // -- Dates ----------------------------------------------------------
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            tonalElevation = AdminTheme.elevation.card,
        ) {
            Column(
                modifier = Modifier.padding(AdminTheme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(AdminTheme.spacing.sm),
            ) {
                val createdLocal = sponsor.createdAt
                    .toLocalDateTime(TimeZone.currentSystemDefault())
                val updatedLocal = sponsor.updatedAt
                    .toLocalDateTime(TimeZone.currentSystemDefault())
                DetailRow(
                    "Created",
                    "${createdLocal.monthNumber}/${createdLocal.dayOfMonth}/${createdLocal.year}",
                )
                DetailRow(
                    "Updated",
                    "${updatedLocal.monthNumber}/${updatedLocal.dayOfMonth}/${updatedLocal.year}",
                )
            }
        }
    }
}
