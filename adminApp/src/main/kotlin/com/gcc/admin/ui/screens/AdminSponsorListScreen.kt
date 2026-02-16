package com.gcc.admin.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gcc.admin.ui.components.DataColumn
import com.gcc.admin.ui.components.DataTable
import com.gcc.admin.ui.components.SearchBar
import com.gcc.admin.ui.theme.*
import com.gcc.admin.viewmodel.SponsorManagementViewModel
import com.gcc.shared.model.Sponsor
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.koinInject

@Composable
fun AdminSponsorListScreen(onSponsorClick: (Int) -> Unit) {
    val viewModel = koinInject<SponsorManagementViewModel>()
    val sponsors by viewModel.sponsors.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(AdminTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(AdminTheme.spacing.lg),
    ) {
        // -- Header ---------------------------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Sponsors", style = MaterialTheme.typography.headlineMedium)
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }

        error?.let {
            Text(it, color = AdminError, style = MaterialTheme.typography.bodySmall)
        }

        // -- Search ---------------------------------------------------------
        SearchBar(
            query = searchQuery,
            onQueryChange = viewModel::onSearchChange,
            placeholder = "Search sponsors by name or email...",
        )

        // -- Table ----------------------------------------------------------
        val columns = remember {
            listOf(
                DataColumn<Sponsor>(
                    header = "Name",
                    weight = 1.5f,
                    content = { sponsor ->
                        Text(sponsor.name, style = MaterialTheme.typography.bodyMedium)
                    },
                ),
                DataColumn<Sponsor>(
                    header = "Email",
                    weight = 1.5f,
                    content = { sponsor ->
                        Text(sponsor.email, style = MaterialTheme.typography.bodyMedium)
                    },
                ),
                DataColumn<Sponsor>(
                    header = "Type",
                    weight = 1f,
                    content = { sponsor ->
                        Text(
                            text = sponsor.type.name.lowercase()
                                .replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                ),
                DataColumn<Sponsor>(
                    header = "Total Donated",
                    weight = 1f,
                    content = { sponsor ->
                        Text(
                            text = formatCents(sponsor.stats.totalDonated),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                ),
                DataColumn<Sponsor>(
                    header = "Blocks Sponsored",
                    weight = 1f,
                    content = { sponsor ->
                        Text(
                            text = sponsor.stats.blocksSponsored.toString(),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                ),
                DataColumn<Sponsor>(
                    header = "Joined",
                    weight = 1f,
                    content = { sponsor ->
                        val local = sponsor.createdAt
                            .toLocalDateTime(TimeZone.currentSystemDefault())
                        Text(
                            text = "${local.monthNumber}/${local.dayOfMonth}/${local.year}",
                            style = MaterialTheme.typography.bodySmall,
                            color = AdminOnSurfaceVariant,
                        )
                    },
                ),
            )
        }

        DataTable(
            items = sponsors,
            columns = columns,
            onRowClick = { sponsor -> onSponsorClick(sponsor.id) },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}

/** Format an integer cents amount as `$X,XXX`. */
private fun formatCents(cents: Int): String {
    val dollars = cents / 100
    return "$${"%,d".format(dollars)}"
}
