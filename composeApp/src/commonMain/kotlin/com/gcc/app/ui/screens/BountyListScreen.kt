package com.gcc.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.components.*
import com.gcc.app.ui.theme.*
import com.gcc.app.viewmodel.BountyViewModel
import com.gcc.app.util.padTwo
import com.gcc.shared.model.Bounty
import com.gcc.shared.model.BountyStatus
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BountyListScreen(
    onBack: () -> Unit = {},
    onBountyClick: (bountyId: Int) -> Unit = {},
) {
    val viewModel = koinInject<BountyViewModel>()
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadBounties()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0),
                title = { Text("Bounties") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            if (uiState.isLoading) {
                SkeletonList(count = 3)
            } else if (uiState.bounties.isEmpty() && uiState.error == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "No bounties available",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextMuted,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Check back soon for new cleanup bounties.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSubtle,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    // Error message
                    uiState.error?.let { error ->
                        item {
                            GccCard(accentColor = CardAccentRed) {
                                Text(error, color = GccError)
                            }
                        }
                    }

                    items(uiState.bounties, key = { it.id }) { bounty ->
                        BountyListItem(
                            bounty = bounty,
                            onClick = { onBountyClick(bounty.id) },
                        )
                    }

                    item { Spacer(modifier = Modifier.height(8.dp)) }
                }
            }
        }
    }
}

@Composable
private fun BountyListItem(
    bounty: Bounty,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dollars = bounty.amount / 100
    val cents = bounty.amount % 100

    GccCard(
        modifier = modifier,
        accentColor = CardAccentGold,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    bounty.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BountyStatusChip(bounty.status)
                    bounty.communityArea?.let { area ->
                        Text(
                            area,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                        )
                    }
                }
                bounty.neighborhood?.let { neighborhood ->
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        neighborhood,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSubtle,
                    )
                }
                bounty.description?.let { desc ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        maxLines = 2,
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                "$$dollars.${cents.padTwo()}",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = GccGreen,
            )
        }
    }
}

@Composable
private fun BountyStatusChip(status: BountyStatus, modifier: Modifier = Modifier) {
    val (text, dotColor, bgColor) = when (status) {
        BountyStatus.OPEN -> Triple("Open", GccAvailable, ChipBgAvailable)
        BountyStatus.FIRST_REFUSAL -> Triple("First Refusal", GccClaimed, ChipBgClaimed)
        BountyStatus.CLAIMED -> Triple("Claimed", GccClaimed, ChipBgClaimed)
        BountyStatus.IN_PROGRESS -> Triple("In Progress", GccInProgress, ChipBgInProgress)
        BountyStatus.COMPLETED -> Triple("Completed", GccCompleted, ChipBgCompleted)
        BountyStatus.EXPIRED -> Triple("Expired", TextMuted, SurfaceMuted)
        BountyStatus.CANCELLED -> Triple("Cancelled", GccError, ChipBgDisputed)
    }
    StatusChip(text = text, dotColor = dotColor, backgroundColor = bgColor, modifier = modifier)
}
