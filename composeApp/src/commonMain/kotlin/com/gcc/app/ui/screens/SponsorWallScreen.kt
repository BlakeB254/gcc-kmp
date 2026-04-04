package com.gcc.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gcc.app.ui.components.AvatarStack
import com.gcc.app.ui.components.GccButton
import com.gcc.app.ui.components.GccButtonVariant
import com.gcc.app.ui.components.GccCard
import com.gcc.app.ui.components.GccLogo
import com.gcc.app.ui.components.SectionHeader
import com.gcc.app.ui.components.SkeletonList
import com.gcc.app.ui.theme.*
import com.gcc.app.viewmodel.SponsorDisplayTier
import com.gcc.app.viewmodel.SponsorWallViewModel
import com.gcc.shared.model.Sponsor
import com.gcc.shared.util.GccFormatters
import kotlinx.coroutines.delay
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SponsorWallScreen(onBack: () -> Unit = {}) {
    val viewModel = koinInject<SponsorWallViewModel>()
    val uiState by viewModel.uiState.collectAsState()
    val spacing = GccTheme.spacing
    val dimensions = GccTheme.dimensions

    LaunchedEffect(Unit) {
        viewModel.loadSponsors()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Our Sponsors") },
                actions = { GccLogo(size = 72.dp, modifier = Modifier.padding(end = 12.dp)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = GccSky,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                ),
            )
        },
    ) { padding ->
        when {
            uiState.isLoading -> {
                Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                    SkeletonList(count = 4)
                }
            }

            uiState.error != null -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = spacing.md),
                    ) {
                        Text("Could not load sponsors", color = TextMuted)
                        Spacer(modifier = Modifier.height(spacing.sm))
                        Text(
                            uiState.error ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = GccError,
                        )
                        Spacer(modifier = Modifier.height(spacing.md))
                        GccButton(
                            text = "Retry",
                            onClick = { viewModel.loadSponsors() },
                            variant = GccButtonVariant.OUTLINED,
                            modifier = Modifier.width(144.dp),
                        )
                    }
                }
            }

            uiState.sponsors.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "No sponsors yet.\nBe the first to support your community!",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            else -> {
                val sponsors = uiState.sponsors
                val topNames = remember(sponsors) {
                    sponsors
                        .sortedByDescending { it.stats.totalDonated }
                        .filter { !it.preferences.isAnonymous }
                        .take(5)
                        .map { it.name }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(bottom = spacing.md),
                ) {
                    // Avatar stack header
                    if (topNames.isNotEmpty()) {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = spacing.md, vertical = spacing.sm),
                            ) {
                                SectionHeader(title = "Top Supporters")
                                Spacer(modifier = Modifier.height(spacing.sm))
                                AvatarStack(
                                    names = topNames,
                                    maxVisible = 5,
                                    size = 34,
                                )
                            }
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(spacing.sm))
                        SectionHeader(
                            title = "${sponsors.size} Sponsors",
                            modifier = Modifier.padding(horizontal = spacing.md),
                        )
                        Spacer(modifier = Modifier.height(spacing.sm))
                    }

                    itemsIndexed(
                        sponsors,
                        key = { _, sponsor -> sponsor.id },
                    ) { index, sponsor ->
                        val animDelay = index * 21
                        var visible by remember { mutableStateOf(false) }
                        LaunchedEffect(Unit) {
                            delay(animDelay.toLong())
                            visible = true
                        }

                        AnimatedVisibility(
                            visible = visible,
                            enter = fadeIn(tween(300)) + slideInVertically(
                                initialOffsetY = { it / 3 },
                                animationSpec = tween(300),
                            ),
                        ) {
                            SponsorCard(
                                sponsor = sponsor,
                                spacing = spacing,
                                modifier = Modifier.padding(
                                    horizontal = spacing.md,
                                    vertical = spacing.xxs,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Sponsor Card
// ---------------------------------------------------------------------------

@Composable
private fun SponsorCard(
    sponsor: Sponsor,
    spacing: GccSpacing,
    modifier: Modifier = Modifier,
) {
    val tier = SponsorWallViewModel.tierFor(sponsor.stats.totalDonated)
    val tierColor = when (tier) {
        SponsorDisplayTier.GOLD -> BadgeGold
        SponsorDisplayTier.SILVER -> BadgeSilver
        SponsorDisplayTier.BRONZE -> BadgeBronze
    }
    val accentColor = when (tier) {
        SponsorDisplayTier.GOLD -> CardAccentSky
        SponsorDisplayTier.SILVER -> BadgeSilver
        SponsorDisplayTier.BRONZE -> BadgeBronze
    }
    val displayName = if (sponsor.preferences.isAnonymous) "Anonymous Sponsor" else sponsor.name

    GccCard(
        modifier = modifier,
        accentColor = accentColor,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Initials avatar
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(tierColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    displayName.take(2).uppercase(),
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                    color = tierColor,
                )
            }

            Spacer(modifier = Modifier.width(spacing.sm))

            // Name + tier badge
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    Text(
                        displayName,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    TierBadge(tier = tier, color = tierColor)
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    Text(
                        "${GccFormatters.formatCents(sponsor.stats.totalDonated)} contributed",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                    if (sponsor.stats.blocksSponsored > 0) {
                        Text(
                            "${sponsor.stats.blocksSponsored} blocks",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSubtle,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Tier Badge
// ---------------------------------------------------------------------------

@Composable
private fun TierBadge(tier: SponsorDisplayTier, color: Color) {
    val label = when (tier) {
        SponsorDisplayTier.GOLD -> "Gold"
        SponsorDisplayTier.SILVER -> "Silver"
        SponsorDisplayTier.BRONZE -> "Bronze"
    }
    Surface(
        shape = RoundedCornerShape(3.dp),
        color = color.copy(alpha = 0.15f),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = color,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}
