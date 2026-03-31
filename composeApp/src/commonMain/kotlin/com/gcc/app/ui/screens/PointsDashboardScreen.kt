package com.gcc.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gcc.app.ui.components.*
import com.gcc.app.ui.theme.*
import com.gcc.app.viewmodel.PointsDashboardViewModel
import com.gcc.shared.model.PointsAction
import com.gcc.shared.model.PointsBreakdown
import com.gcc.shared.model.PointsLedgerEntry
import com.gcc.shared.model.PointsSummary
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PointsDashboardScreen(onBack: () -> Unit = {}) {
    val viewModel = koinInject<PointsDashboardViewModel>()
    val uiState by viewModel.uiState.collectAsState()
    val spacing = GccTheme.spacing

    LaunchedEffect(Unit) {
        viewModel.loadPoints()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Points & Rewards") },
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
        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    SkeletonList(count = 4)
                }
            }

            uiState.error != null -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Could not load points", color = TextMuted)
                        Spacer(modifier = Modifier.height(spacing.sm))
                        Text(
                            uiState.error ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = GccError,
                        )
                        Spacer(modifier = Modifier.height(spacing.md))
                        TextButton(onClick = { viewModel.loadPoints() }) {
                            Text("Retry", color = GccGreen)
                        }
                    }
                }
            }

            else -> {
                val summary = uiState.pointsSummary ?: PointsSummary()

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(
                        start = spacing.md,
                        end = spacing.md,
                        top = spacing.md,
                        bottom = spacing.xxl,
                    ),
                    verticalArrangement = Arrangement.spacedBy(spacing.md),
                ) {
                    // Hero card - total points
                    item {
                        HeroPointsCard(
                            totalPoints = summary.totalPoints,
                            spacing = spacing,
                        )
                    }

                    // Breakdown section
                    item {
                        SectionHeader(title = "Points Breakdown")
                    }

                    item {
                        BreakdownGrid(
                            breakdown = summary.breakdown,
                            spacing = spacing,
                        )
                    }

                    // Next milestone card
                    item {
                        NextMilestoneCard(totalPoints = summary.totalPoints, spacing = spacing)
                    }

                    // How to earn section
                    item {
                        SectionHeader(title = "How to Earn Points")
                    }

                    item {
                        GccCard {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                EarnTip(emoji = "\uD83D\uDCF8", action = "Report a dirty spot", points = "+5 pts")
                                EarnTip(emoji = "\uD83E\uDDF9", action = "Volunteer cleanup", points = "+20 pts")
                                EarnTip(emoji = "\uD83D\uDD0D", action = "Verify a spot (dirty)", points = "+3 pts")
                                EarnTip(emoji = "\u2728", action = "Verify a spot (clean)", points = "+5 pts")
                                EarnTip(emoji = "\u2705", action = "Report already clean", points = "+2 pts")
                            }
                        }
                    }

                    // Recent activity
                    if (summary.recentEntries.isNotEmpty()) {
                        item {
                            SectionHeader(title = "Recent Activity")
                        }

                        items(
                            summary.recentEntries,
                            key = { it.id },
                        ) { entry ->
                            ActivityRow(entry = entry, spacing = spacing)
                        }
                    } else {
                        item {
                            GccCard {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text("\uD83C\uDF1F", style = MaterialTheme.typography.displaySmall)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        "Start earning!",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    )
                                    Text(
                                        "Report spots, verify cleanups, and volunteer\nto see your activity here.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextMuted,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Hero Points Card
// ---------------------------------------------------------------------------

@Composable
private fun HeroPointsCard(totalPoints: Int, spacing: GccSpacing) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(GccTheme.dimensions.cardRadius),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(GccGreen, GccGreenDark),
                    )
                )
                .padding(spacing.lg),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Total Points",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = 0.8f),
                )
                Spacer(modifier = Modifier.height(spacing.xs))
                Text(
                    "$totalPoints",
                    style = MaterialTheme.typography.displayMedium.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                    color = GccGoldLight,
                )
                Spacer(modifier = Modifier.height(spacing.xs))
                Text(
                    "Keep cleaning to earn more!",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Breakdown Grid
// ---------------------------------------------------------------------------

@Composable
private fun BreakdownGrid(breakdown: PointsBreakdown, spacing: GccSpacing) {
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            StatCard(
                value = "${breakdown.spotCreated}",
                label = "Spots Created",
                accentColor = CardAccentGreen,
                modifier = Modifier.weight(1f),
            )
            StatCard(
                value = "${breakdown.volunteerCleanup}",
                label = "Cleanups",
                accentColor = CardAccentGold,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            StatCard(
                value = "${breakdown.verificationDirty}",
                label = "Dirty Verified",
                accentColor = CardAccentBlue,
                modifier = Modifier.weight(1f),
            )
            StatCard(
                value = "${breakdown.verificationClean}",
                label = "Clean Verified",
                accentColor = CardAccentPurple,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            StatCard(
                value = "${breakdown.alreadyCleanReport}",
                label = "Already Clean",
                accentColor = CardAccentGreen,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

// ---------------------------------------------------------------------------
// Activity Row
// ---------------------------------------------------------------------------

@Composable
private fun ActivityRow(entry: PointsLedgerEntry, spacing: GccSpacing) {
    val (emoji, description) = when (entry.action) {
        PointsAction.SPOT_CREATED -> "\uD83D\uDCF8" to "Spot Reported"
        PointsAction.VERIFICATION_DIRTY -> "\uD83D\uDD0D" to "Verified Dirty"
        PointsAction.VERIFICATION_CLEAN -> "\u2728" to "Verified Clean"
        PointsAction.VOLUNTEER_CLEANUP -> "\uD83E\uDDF9" to "Volunteer Cleanup"
        PointsAction.ALREADY_CLEAN_REPORT -> "\u2705" to "Already Clean"
    }

    val localDate = entry.createdAt.toLocalDateTime(TimeZone.currentSystemDefault())
    val dateStr = "${localDate.monthNumber}/${localDate.dayOfMonth}/${localDate.year}"

    GccCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                emoji,
                fontSize = 24.sp,
            )
            Spacer(modifier = Modifier.width(spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
                Text(
                    dateStr,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSubtle,
                )
            }
            Text(
                "+${entry.points}",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                ),
                color = GccGold,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Next Milestone Card
// ---------------------------------------------------------------------------

@Composable
private fun NextMilestoneCard(totalPoints: Int, spacing: GccSpacing) {
    data class Milestone(val points: Int, val name: String, val emoji: String)
    val milestones = listOf(
        Milestone(10, "First Steps", "\uD83D\uDC63"),
        Milestone(50, "Community Helper", "\uD83E\uDD1D"),
        Milestone(100, "Block Champion", "\uD83C\uDFC6"),
        Milestone(250, "Neighborhood Hero", "\uD83E\uDDB8"),
        Milestone(500, "Clean Streets Legend", "\uD83C\uDF1F"),
        Milestone(1000, "GCC Ambassador", "\uD83C\uDF0D"),
    )

    val next = milestones.firstOrNull { it.points > totalPoints }
    val current = milestones.lastOrNull { it.points <= totalPoints }

    if (next != null) {
        val progress = totalPoints.toFloat() / next.points
        GccCard(accentColor = CardAccentGold) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(next.emoji, style = MaterialTheme.typography.headlineMedium)
                Spacer(modifier = Modifier.width(spacing.sm))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Next: ${next.name}",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = GccGold,
                        trackColor = GccGold.copy(alpha = 0.15f),
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        "$totalPoints / ${next.points} points",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                    )
                }
            }
        }
    } else if (current != null) {
        GccCard(accentColor = CardAccentGold) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(current.emoji, style = MaterialTheme.typography.headlineMedium)
                Spacer(modifier = Modifier.width(spacing.sm))
                Text(
                    "Max rank: ${current.name}!",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = GccGold,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Earn Tip Row
// ---------------------------------------------------------------------------

@Composable
private fun EarnTip(emoji: String, action: String, points: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(emoji, style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            action,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Text(
            points,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = GccGold,
        )
    }
}
