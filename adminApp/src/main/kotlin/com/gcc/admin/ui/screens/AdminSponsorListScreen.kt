package com.gcc.admin.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gcc.admin.ui.components.*
import com.gcc.admin.ui.theme.*
import com.gcc.admin.viewmodel.SponsorManagementViewModel
import org.koin.compose.koinInject

/**
 * List screen showing all sponsors with search capability.
 */
@Composable
fun AdminSponsorListScreen(
    onSponsorClick: (Int) -> Unit,
    viewModel: SponsorManagementViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()

    AdminScaffold(
        title = "Sponsors",
        subtitle = "Management",
        scrollable = false,
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            SearchBar(
                query = state.searchQuery,
                onQueryChange = { viewModel.searchSponsors(it) },
                placeholder = "Search sponsors...",
                modifier = Modifier.padding(bottom = 16.dp),
            )

            if (state.isLoading) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else {
                DataTable(
                    items = state.sponsors,
                    columns = listOf(
                        TableColumn(header = "ID", weight = 0.5f) { sponsor ->
                            Text(
                                text = sponsor.id.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        TableColumn(header = "Name", weight = 1.5f) { sponsor ->
                            Text(
                                text = sponsor.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        TableColumn(header = "Type", weight = 1f) { sponsor ->
                            StatusBadge(
                                text = sponsor.type.name,
                                color = when (sponsor.type) {
                                    com.gcc.shared.model.SponsorType.INDIVIDUAL -> StatusInProgress
                                    com.gcc.shared.model.SponsorType.BUSINESS -> StatusAvailable
                                    com.gcc.shared.model.SponsorType.ORGANIZATION -> StatusPending
                                    com.gcc.shared.model.SponsorType.FOUNDATION -> StatusWarning
                                },
                            )
                        },
                        TableColumn(header = "Total Funded", weight = 1f) { sponsor ->
                            Text(
                                text = "$" + (sponsor.stats.totalDonated / 100).toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        TableColumn(header = "Blocks", weight = 0.8f) { sponsor ->
                            Text(
                                text = sponsor.stats.blocksSponsored.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    ),
                    onRowClick = { sponsor -> onSponsorClick(sponsor.id) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
