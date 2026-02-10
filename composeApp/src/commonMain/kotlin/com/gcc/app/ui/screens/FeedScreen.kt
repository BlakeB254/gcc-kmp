package com.gcc.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.components.GccCard
import com.gcc.app.ui.components.SkeletonList
import com.gcc.app.ui.theme.*
import com.gcc.app.util.padTwo
import com.gcc.app.viewmodel.FeedViewModel
import com.gcc.shared.model.EarlyAdopterTier
import com.gcc.shared.model.FeedItem
import com.gcc.shared.model.FeedItemType
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class, kotlin.time.ExperimentalTime::class)
@Composable
fun FeedScreen(
    onBack: () -> Unit = {},
) {
    val viewModel = koinInject<FeedViewModel>()
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        viewModel.loadFeed()
    }

    // Infinite scroll: detect when last item is visible
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            lastVisibleIndex >= totalItems - 3 && !uiState.isLoadingMore && uiState.hasMore && !uiState.isLoading
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            viewModel.loadMore()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0),
                title = { Text("Community Feed") },
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
                SkeletonList(count = 5)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(13.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    // Filter chips row
                    item {
                        FilterChipsRow(
                            selectedFilter = uiState.selectedFilter,
                            onFilterSelected = { viewModel.setFilter(it) },
                        )
                    }

                    // Error message
                    uiState.error?.let { error ->
                        item {
                            GccCard(
                                accentColor = CardAccentRed,
                                modifier = Modifier.padding(horizontal = 13.dp),
                            ) {
                                Text(error, color = GccError)
                            }
                        }
                    }

                    if (uiState.items.isEmpty() && uiState.error == null) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(32.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "No activity yet.\nCheck back soon!",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextMuted,
                                )
                            }
                        }
                    }

                    items(uiState.items, key = { it.id }) { item ->
                        FeedCard(
                            item = item,
                            modifier = Modifier.padding(horizontal = 13.dp),
                        )
                    }

                    // Load more indicator
                    if (uiState.isLoadingMore) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(13.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    color = GccGreen,
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                        }
                    }

                    item { Spacer(modifier = Modifier.height(8.dp)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterChipsRow(
    selectedFilter: FeedItemType?,
    onFilterSelected: (FeedItemType?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selectedFilter == null,
            onClick = { onFilterSelected(null) },
            label = { Text("All", style = MaterialTheme.typography.labelMedium) },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = GccGreen,
                selectedLabelColor = Color.White,
                containerColor = SurfaceMuted,
                labelColor = TextMuted,
            ),
            shape = RoundedCornerShape(8.dp),
        )

        data class FilterOption(val type: FeedItemType, val label: String)
        val filters = listOf(
            FilterOption(FeedItemType.SPOT_REPORT, "Spots"),
            FilterOption(FeedItemType.BOUNTY_CREATED, "Bounties"),
            FilterOption(FeedItemType.JOB_COMPLETED, "Jobs"),
            FilterOption(FeedItemType.VOLUNTEER_CLEANUP, "Cleanups"),
            FilterOption(FeedItemType.BADGE_UNLOCKED, "Badges"),
        )

        filters.forEach { filter ->
            FilterChip(
                selected = selectedFilter == filter.type,
                onClick = { onFilterSelected(filter.type) },
                label = { Text(filter.label, style = MaterialTheme.typography.labelMedium) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = GccGreen,
                    selectedLabelColor = Color.White,
                    containerColor = SurfaceMuted,
                    labelColor = TextMuted,
                ),
                shape = RoundedCornerShape(8.dp),
            )
        }
    }
}

@Composable
private fun FeedCard(
    item: FeedItem,
    modifier: Modifier = Modifier,
) {
    val accentColor = when (item.type) {
        FeedItemType.SPOT_REPORT -> CardAccentPurple
        FeedItemType.BOUNTY_CREATED, FeedItemType.BOUNTY_COMPLETED -> CardAccentGold
        FeedItemType.JOB_COMPLETED -> CardAccentGreen
        FeedItemType.VOLUNTEER_CLEANUP -> CardAccentBlue
        FeedItemType.BADGE_UNLOCKED -> CardAccentRed
    }

    GccCard(
        modifier = modifier,
        accentColor = accentColor,
    ) {
        // Actor row: initials circle + name + tier + time
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Initials circle (34dp)
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    item.actorName.take(2).uppercase(),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                    color = accentColor,
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.actorName,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (item.actorTier != EarlyAdopterTier.STANDARD) {
                        Spacer(modifier = Modifier.width(5.dp))
                        FeedTierBadge(item.actorTier)
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            @OptIn(kotlin.time.ExperimentalTime::class)
            Text(
                timeAgo(item.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = TextSubtle,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Title
        Text(
            item.title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        )

        // Description
        if (item.description.isNotBlank()) {
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                item.description,
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Amount / Points badge row
        val hasAmountOrPoints = item.amount != null || item.points != null
        if (hasAmountOrPoints) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item.amount?.let { amount ->
                    val dollars = amount / 100
                    val cents = amount % 100
                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = GccGreen.copy(alpha = 0.1f),
                    ) {
                        Text(
                            "$$dollars.${cents.padTwo()}",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                            ),
                            color = GccGreen,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
                item.points?.let { points ->
                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = CardAccentPurple.copy(alpha = 0.1f),
                    ) {
                        Text(
                            "+$points pts",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                            ),
                            color = CardAccentPurple,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }

        // Community area
        item.communityArea?.let { area ->
            Spacer(modifier = Modifier.height(5.dp))
            Text(
                area,
                style = MaterialTheme.typography.labelSmall,
                color = TextSubtle,
            )
        }
    }
}

@Composable
private fun FeedTierBadge(tier: EarlyAdopterTier) {
    val (color, label) = when (tier) {
        EarlyAdopterTier.FOUNDER -> BadgeGold to "Founder"
        EarlyAdopterTier.PIONEER -> BadgeSilver to "Pioneer"
        EarlyAdopterTier.EARLY -> BadgeBronze to "Early"
        EarlyAdopterTier.STANDARD -> TextMuted to "Standard"
    }
    Surface(
        shape = RoundedCornerShape(3.dp),
        color = color.copy(alpha = 0.15f),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
            ),
            color = color,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

@OptIn(kotlin.time.ExperimentalTime::class)
private fun timeAgo(timestamp: kotlinx.datetime.Instant): String {
    val now = kotlin.time.Clock.System.now()
    val nowEpoch = now.toEpochMilliseconds()
    val thenEpoch = timestamp.toEpochMilliseconds()
    val diffMs = nowEpoch - thenEpoch
    val diffSeconds = diffMs / 1000
    val diffMinutes = diffSeconds / 60
    val diffHours = diffMinutes / 60
    val diffDays = diffHours / 24

    return when {
        diffSeconds < 60 -> "just now"
        diffMinutes < 60 -> "${diffMinutes}m"
        diffHours < 24 -> "${diffHours}h"
        diffDays < 7 -> "${diffDays}d"
        diffDays < 30 -> "${diffDays / 7}w"
        else -> {
            @Suppress("DEPRECATION")
            val dt = timestamp.toLocalDateTime(TimeZone.of("America/Chicago"))
            @Suppress("DEPRECATION")
            "${dt.monthNumber}/${dt.dayOfMonth}"
        }
    }
}
