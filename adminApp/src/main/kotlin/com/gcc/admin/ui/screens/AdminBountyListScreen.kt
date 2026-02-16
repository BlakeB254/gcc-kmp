package com.gcc.admin.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gcc.admin.ui.components.BountyStatusBadge
import com.gcc.admin.ui.components.DataColumn
import com.gcc.admin.ui.components.DataTable
import com.gcc.admin.ui.theme.*
import com.gcc.admin.viewmodel.BountyManagementViewModel
import com.gcc.shared.model.Bounty
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.koinInject

@Composable
fun AdminBountyListScreen(onBountyClick: (Int) -> Unit) {
    val viewModel = koinInject<BountyManagementViewModel>()
    val bounties by viewModel.bounties.collectAsState()
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
            Text("Bounties", style = MaterialTheme.typography.headlineMedium)
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }

        error?.let {
            Text(it, color = AdminError, style = MaterialTheme.typography.bodySmall)
        }

        // -- Table ----------------------------------------------------------
        val columns = remember {
            listOf(
                DataColumn<Bounty>(
                    header = "ID",
                    weight = 0.5f,
                    content = { bounty ->
                        Text("#${bounty.id}", style = MaterialTheme.typography.bodyMedium)
                    },
                ),
                DataColumn<Bounty>(
                    header = "Title",
                    weight = 2f,
                    content = { bounty ->
                        Text(bounty.title, style = MaterialTheme.typography.bodyMedium)
                    },
                ),
                DataColumn<Bounty>(
                    header = "Amount",
                    weight = 0.8f,
                    content = { bounty ->
                        Text(
                            text = "$${"%,d".format(bounty.amount / 100)}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                ),
                DataColumn<Bounty>(
                    header = "Status",
                    weight = 1f,
                    content = { bounty ->
                        BountyStatusBadge(bounty.status)
                    },
                ),
                DataColumn<Bounty>(
                    header = "Location",
                    weight = 1.2f,
                    content = { bounty ->
                        Text(
                            text = bounty.communityArea ?: "N/A",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                ),
                DataColumn<Bounty>(
                    header = "Pledges",
                    weight = 0.6f,
                    content = { bounty ->
                        Text(
                            text = bounty.pledgeCount.toString(),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                ),
                DataColumn<Bounty>(
                    header = "Created",
                    weight = 0.9f,
                    content = { bounty ->
                        val local = bounty.createdAt
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
            items = bounties,
            columns = columns,
            onRowClick = { bounty -> onBountyClick(bounty.id) },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}
