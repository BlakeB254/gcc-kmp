package com.gcc.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.components.*
import com.gcc.app.ui.theme.*
import com.gcc.app.util.fmt
import com.gcc.app.viewmodel.ImpactViewModel
import com.gcc.shared.model.CleanlinessStats
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImpactScreen(onBack: () -> Unit = {}) {
    val viewModel = koinInject<ImpactViewModel>()
    val uiState by viewModel.uiState.collectAsState()
    val spacing = GccTheme.spacing

    LaunchedEffect(Unit) {
        viewModel.loadImpact()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        GccLogo(size = 24.dp)
                        Text("Community Impact")
                    }
                },
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
                    CircularProgressIndicator(color = GccGreen)
                }
            }

            uiState.error != null -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Could not load impact data", color = TextMuted)
                        Spacer(modifier = Modifier.height(spacing.sm))
                        Text(
                            uiState.error ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = GccError,
                        )
                        Spacer(modifier = Modifier.height(spacing.md))
                        TextButton(onClick = { viewModel.loadImpact() }) {
                            Text("Retry", color = GccGreen)
                        }
                    }
                }
            }

            uiState.overview == null -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp),
                    ) {
                        Text("\uD83C\uDF0D", style = MaterialTheme.typography.displayLarge)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Building Community Impact",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Every spot you report, every block you clean, and every verification you make contributes to a cleaner neighborhood.\n\nYour impact data will appear here as the community takes action.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMuted,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            else -> {
                val overview = uiState.overview!!

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(
                        start = spacing.md,
                        end = spacing.md,
                        top = spacing.md,
                        bottom = spacing.xl,
                    ),
                    verticalArrangement = Arrangement.spacedBy(spacing.md),
                ) {
                    // 1. City Score Hero
                    item {
                        val scoreColor = HeatColors.forCleanlinessScore(overview.cityScore)
                        GccCard(accentColor = scoreColor) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    "City Cleanliness",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextMuted,
                                )
                                Spacer(modifier = Modifier.height(spacing.sm))
                                HeatBadge(label = "Score", score = overview.cityScore)
                            }
                        }
                    }

                    // 2. Summary Stats
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                        ) {
                            StatCard(
                                value = overview.totalActiveSpots.toString(),
                                label = "Active Spots",
                                accentColor = CardAccentRed,
                                modifier = Modifier.weight(1f),
                            )
                            StatCard(
                                value = overview.totalResolvedSpots.toString(),
                                label = "Resolved",
                                accentColor = CardAccentGreen,
                                modifier = Modifier.weight(1f),
                            )
                            StatCard(
                                value = "${overview.avgMessDurationHours.fmt(1)}h",
                                label = "Avg Duration",
                                accentColor = CardAccentBlue,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    // 3. Area Breakdown
                    if (overview.areas.isNotEmpty()) {
                        item {
                            SectionHeader(title = "Neighborhood Scores")
                        }

                        items(overview.areas, key = { it.area }) { area ->
                            AreaCard(area = area, spacing = spacing)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AreaCard(
    area: CleanlinessStats,
    spacing: GccSpacing,
) {
    var expanded by remember { mutableStateOf(false) }
    val hasStreets = area.streets.isNotEmpty()

    GccCard(accentColor = HeatColors.forCleanlinessScore(area.cleanlinessScore)) {
        // Header row: area name + score badge
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                area.area,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f, fill = false),
            )
            HeatBadge(label = "Score", score = area.cleanlinessScore)
        }

        Spacer(modifier = Modifier.height(spacing.sm))

        // Stats row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            MiniStat(value = area.activeSpots.toString(), label = "Active")
            MiniStat(value = area.resolvedSpots.toString(), label = "Resolved")
            MiniStat(value = area.totalVerifications.toString(), label = "Verified")
            MiniStat(value = area.totalVolunteerCleanups.toString(), label = "Cleanups")
        }

        // Expandable streets section
        if (hasStreets) {
            Spacer(modifier = Modifier.height(spacing.xs))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (expanded) "Hide streets" else "${area.streets.size} streets",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSubtle,
                )
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = TextSubtle,
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column(
                    modifier = Modifier.padding(top = spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(spacing.xxs),
                ) {
                    area.streets.forEach { street ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                street.street,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                HeatBadge(label = "", score = street.score)
                                Text(
                                    "${street.activeSpots} active",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextMuted,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniStat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
        )
    }
}
