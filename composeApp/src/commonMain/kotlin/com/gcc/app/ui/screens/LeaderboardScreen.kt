package com.gcc.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gcc.app.ui.theme.*
import com.gcc.app.util.fmt
import com.gcc.app.util.padTwo
import com.gcc.app.viewmodel.LeaderboardPeriod
import com.gcc.app.viewmodel.LeaderboardViewModel
import com.gcc.shared.model.EarlyAdopterTier
import com.gcc.shared.model.LeaderboardEntry
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeaderboardScreen(
    onBack: () -> Unit = {},
) {
    val viewModel = koinInject<LeaderboardViewModel>()
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val spacing = GccTheme.spacing
    val dimensions = GccTheme.dimensions

    LaunchedEffect(Unit) {
        viewModel.loadLeaderboard()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0),
                title = { Text("Leaderboard") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = GccGreen,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Period toggle tabs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.md, vertical = spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                LeaderboardPeriod.entries.forEach { period ->
                    FilterChip(
                        selected = uiState.selectedPeriod == period,
                        onClick = { viewModel.selectPeriod(period) },
                        label = {
                            Text(
                                period.label,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = GccGreen,
                            selectedLabelColor = Color.White,
                            containerColor = SurfaceMuted,
                            labelColor = TextMuted,
                        ),
                        shape = RoundedCornerShape(spacing.sm),
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Crossfade(
                targetState = uiState.selectedPeriod,
                animationSpec = tween(durationMillis = 300),
            ) { _ ->
                if (uiState.isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = GccGreen)
                    }
                } else if (uiState.error != null) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Could not load leaderboard", color = TextMuted)
                            Spacer(modifier = Modifier.height(spacing.sm))
                            Text(
                                uiState.error ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = GccError,
                            )
                            Spacer(modifier = Modifier.height(spacing.md))
                            TextButton(onClick = { viewModel.loadLeaderboard() }) {
                                Text("Retry", color = GccGreen)
                            }
                        }
                    }
                } else if (uiState.entries.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "No leaderboard data yet.\nComplete jobs to get ranked!",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMuted,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    val top3 = uiState.entries.take(3)
                    val rest = uiState.entries.drop(3)

                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                bottom = if (uiState.currentUserRank != null) {
                                    dimensions.cardMinHeight + spacing.sm
                                } else {
                                    spacing.sm
                                },
                            ),
                        ) {
                            // Top 3 Podium
                            if (top3.isNotEmpty()) {
                                item {
                                    PodiumSection(
                                        top3 = top3,
                                        currentUserId = uiState.currentUserId,
                                        spacing = spacing,
                                        dimensions = dimensions,
                                    )
                                }
                            }

                            // Remaining ranks
                            if (rest.isNotEmpty()) {
                                item {
                                    Spacer(modifier = Modifier.height(spacing.sm))
                                }

                                itemsIndexed(
                                    rest,
                                    key = { _, entry -> entry.workerId },
                                ) { index, entry ->
                                    val animDelay = index * 21 // 21ms stagger (Fibonacci)
                                    var visible by remember { mutableStateOf(false) }
                                    LaunchedEffect(Unit) {
                                        delay(animDelay.toLong())
                                        visible = true
                                    }

                                    Box {
                                        androidx.compose.animation.AnimatedVisibility(
                                            visible = visible,
                                            enter = fadeIn(tween(300)) + slideInVertically(
                                                initialOffsetY = { it / 3 },
                                                animationSpec = tween(300),
                                            ),
                                        ) {
                                            RankRow(
                                                entry = entry,
                                                isCurrentUser = entry.workerId == uiState.currentUserId,
                                                spacing = spacing,
                                                dimensions = dimensions,
                                            )
                                        }
                                    }
                                }

                                item { Spacer(modifier = Modifier.height(spacing.sm)) }
                            }
                        }

                        // Your Position Card (pinned bottom)
                        if (uiState.currentUserRank != null) {
                            val currentEntry = uiState.entries.find {
                                it.workerId == uiState.currentUserId
                            }
                            if (currentEntry != null) {
                                YourPositionCard(
                                    entry = currentEntry,
                                    spacing = spacing,
                                    dimensions = dimensions,
                                    modifier = Modifier.align(Alignment.BottomCenter),
                                    onTap = {
                                        coroutineScope.launch {
                                            val targetIndex = uiState.entries.indexOfFirst {
                                                it.workerId == uiState.currentUserId
                                            }
                                            if (targetIndex >= 3) {
                                                // Account for podium item + spacer item
                                                listState.animateScrollToItem(targetIndex - 3 + 2)
                                            } else {
                                                listState.animateScrollToItem(0)
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Podium Section (Top 3)
// ---------------------------------------------------------------------------

@Composable
private fun PodiumSection(
    top3: List<LeaderboardEntry>,
    currentUserId: Int?,
    spacing: GccSpacing,
    dimensions: GccDimensions,
) {
    val first = top3.getOrNull(0)
    val second = top3.getOrNull(1)
    val third = top3.getOrNull(2)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(144.dp) // Fibonacci: 144
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        GccGreen.copy(alpha = 0.08f),
                        Color.Transparent,
                    )
                )
            )
            .padding(horizontal = spacing.md),
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Bottom,
        ) {
            // #2 - left
            if (second != null) {
                PodiumEntry(
                    entry = second,
                    avatarSize = dimensions.avatarSm, // 34dp
                    ringColor = BadgeSilver,
                    podiumHeight = 55.dp, // Fibonacci
                    isCurrentUser = second.workerId == currentUserId,
                    showCrown = false,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }

            // #1 - center, tallest
            if (first != null) {
                PodiumEntry(
                    entry = first,
                    avatarSize = dimensions.avatarMd, // 55dp
                    ringColor = BadgeGold,
                    podiumHeight = dimensions.cardMinHeight, // 89dp Fibonacci
                    isCurrentUser = first.workerId == currentUserId,
                    showCrown = true,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }

            // #3 - right
            if (third != null) {
                PodiumEntry(
                    entry = third,
                    avatarSize = dimensions.avatarSm, // 34dp
                    ringColor = BadgeBronze,
                    podiumHeight = 34.dp, // Fibonacci
                    isCurrentUser = third.workerId == currentUserId,
                    showCrown = false,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PodiumEntry(
    entry: LeaderboardEntry,
    avatarSize: Dp,
    ringColor: Color,
    podiumHeight: Dp,
    isCurrentUser: Boolean,
    showCrown: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Crown for #1
        if (showCrown) {
            Text(
                "\uD83D\uDC51", // Crown emoji
                fontSize = 20.sp,
            )
        }

        // Avatar circle with metallic ring
        Box(
            modifier = Modifier
                .size(avatarSize + 8.dp)
                .clip(CircleShape)
                .border(3.dp, ringColor, CircleShape)
                .then(
                    if (isCurrentUser) Modifier.border(
                        2.dp,
                        GccGreen,
                        CircleShape,
                    ) else Modifier
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(avatarSize)
                    .clip(CircleShape)
                    .background(ringColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    entry.name.take(2).uppercase(),
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = if (avatarSize >= 55.dp) 18.sp else 12.sp,
                    ),
                    color = ringColor,
                )
            }
        }

        Spacer(modifier = Modifier.height(3.dp)) // Fibonacci xxs

        // Name
        Text(
            entry.name.split(" ").firstOrNull() ?: "",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        // Stats
        Text(
            "${entry.jobsCompleted} jobs",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
        )

        // Tier badge
        if (entry.earlyAdopterTier != EarlyAdopterTier.STANDARD) {
            TierBadgeSmall(tier = entry.earlyAdopterTier)
        }

        // Podium pedestal
        Box(
            modifier = Modifier
                .width(55.dp) // Fibonacci
                .height(podiumHeight)
                .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            ringColor.copy(alpha = 0.3f),
                            ringColor.copy(alpha = 0.1f),
                        )
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "#${entry.rank}",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = ringColor,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Rank Row (for ranks 4+)
// ---------------------------------------------------------------------------

@Composable
private fun RankRow(
    entry: LeaderboardEntry,
    isCurrentUser: Boolean,
    spacing: GccSpacing,
    dimensions: GccDimensions,
) {
    val highlightColor = if (isCurrentUser) GccGreen.copy(alpha = 0.08f) else Color.Transparent
    val borderMod = if (isCurrentUser) {
        Modifier.border(
            2.dp,
            GccGreen.copy(alpha = 0.4f),
            RoundedCornerShape(dimensions.cardRadius),
        )
    } else {
        Modifier
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.md, vertical = spacing.xxs)
            .then(borderMod),
        shape = RoundedCornerShape(dimensions.cardRadius), // 13dp
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(highlightColor)
                .height(dimensions.cardMinHeight) // 89dp Fibonacci
                .padding(horizontal = spacing.md, vertical = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Rank circle (34dp)
            Box(
                modifier = Modifier
                    .size(dimensions.avatarSm) // 34dp
                    .clip(CircleShape)
                    .background(SurfaceMuted),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "#${entry.rank}",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                    color = TextMuted,
                )
            }

            Spacer(modifier = Modifier.width(spacing.sm)) // 8dp

            // Avatar (55dp)
            Box(
                modifier = Modifier
                    .size(dimensions.avatarMd) // 55dp
                    .clip(CircleShape)
                    .background(GccGreen.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    entry.name.take(2).uppercase(),
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                    color = GccGreen,
                )
            }

            Spacer(modifier = Modifier.width(spacing.sm)) // 8dp

            // Name + stats
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.name,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isCurrentUser) {
                        Spacer(modifier = Modifier.width(spacing.xs)) // 5dp
                        Surface(
                            shape = RoundedCornerShape(spacing.xxs), // 3dp
                            color = GccGreen,
                        ) {
                            Text(
                                "YOU",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 9.sp,
                                ),
                                color = Color.White,
                                modifier = Modifier.padding(
                                    horizontal = spacing.xs,
                                    vertical = 1.dp,
                                ),
                            )
                        }
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${entry.jobsCompleted} jobs",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                    if (entry.badgeCount > 0) {
                        Text(
                            "${entry.badgeCount} badges",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSubtle,
                        )
                    }
                    if (entry.earlyAdopterTier != EarlyAdopterTier.STANDARD) {
                        TierBadgeSmall(tier = entry.earlyAdopterTier)
                    }
                }
            }

            // Earnings
            Column(horizontalAlignment = Alignment.End) {
                val dollars = entry.totalEarnings / 100
                val cents = entry.totalEarnings % 100
                Text(
                    "$$dollars.${cents.padTwo()}",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                    color = GccGreen,
                )
                entry.averageRating?.let { rating ->
                    Text(
                        "${rating.fmt(1)} avg",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSubtle,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Your Position Card (pinned bottom)
// ---------------------------------------------------------------------------

@Composable
private fun YourPositionCard(
    entry: LeaderboardEntry,
    spacing: GccSpacing,
    dimensions: GccDimensions,
    modifier: Modifier = Modifier,
    onTap: () -> Unit = {},
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.md, vertical = spacing.xs)
            .clickable(onClick = onTap)
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(dimensions.cardRadius),
            ),
        shape = RoundedCornerShape(dimensions.cardRadius), // 13dp
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            GccGreen.copy(alpha = 0.06f),
                            Color.Transparent,
                        )
                    )
                )
                .height(dimensions.cardMinHeight) // 89dp
                .padding(horizontal = spacing.md, vertical = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Rank
            Box(
                modifier = Modifier
                    .size(dimensions.avatarSm) // 34dp
                    .clip(CircleShape)
                    .background(GccGreen),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "#${entry.rank}",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                    color = Color.White,
                )
            }

            Spacer(modifier = Modifier.width(spacing.sm))

            // Your info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Your Position",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
                Text(
                    entry.name,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }

            // Tap hint
            Column(horizontalAlignment = Alignment.End) {
                val dollars = entry.totalEarnings / 100
                val cents = entry.totalEarnings % 100
                Text(
                    "$$dollars.${cents.padTwo()}",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                    color = GccGreen,
                )
                Text(
                    "Tap to scroll",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSubtle,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Tier Badge (small inline)
// ---------------------------------------------------------------------------

@Composable
private fun TierBadgeSmall(tier: EarlyAdopterTier) {
    val color: Color
    val label: String
    when (tier) {
        EarlyAdopterTier.FOUNDER -> {
            color = BadgeGold
            label = "Founder"
        }
        EarlyAdopterTier.PIONEER -> {
            color = BadgeSilver
            label = "Pioneer"
        }
        EarlyAdopterTier.EARLY -> {
            color = BadgeBronze
            label = "Early"
        }
        EarlyAdopterTier.STANDARD -> {
            color = TextMuted
            label = "Standard"
        }
    }

    Surface(
        shape = RoundedCornerShape(3.dp), // Fibonacci xxs
        color = color.copy(alpha = 0.15f),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = color,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp), // Fibonacci xs
        )
    }
}
