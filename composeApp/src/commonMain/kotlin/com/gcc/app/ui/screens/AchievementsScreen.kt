package com.gcc.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.components.GccLogo
import com.gcc.app.ui.gamification.BadgeCard
import com.gcc.app.ui.theme.*
import com.gcc.app.viewmodel.ProfileViewModel
import com.gcc.shared.model.BadgeCategory
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AchievementsScreen(
    onBack: () -> Unit = {},
) {
    val viewModel = koinInject<ProfileViewModel>()
    val uiState by viewModel.uiState.collectAsState()
    var selectedTabIndex by remember { mutableStateOf(0) }

    val categoryTabs = listOf("All") + BadgeCategory.entries.map { it.displayName() }

    LaunchedEffect(Unit) { viewModel.loadProfile() }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        GccLogo(size = 28.dp)
                        Text("Achievements")
                    }
                },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("\u2190 Back", color = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = GccGreen,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                ),
            )
        }
    ) { padding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = GccGreen)
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                // Progress summary card
                val unlockedCount = uiState.badges.count { it.isUnlocked }
                val totalCount = uiState.badges.size
                val progress = if (totalCount > 0) unlockedCount.toFloat() / totalCount else 0f

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceMuted),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "$unlockedCount / $totalCount Badges Earned",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                            color = GccGreen,
                            trackColor = DividerColor,
                        )
                    }
                }

                // Category tabs
                ScrollableTabRow(
                    selectedTabIndex = selectedTabIndex,
                    containerColor = Color.Transparent,
                    contentColor = GccGreen,
                    edgePadding = 16.dp,
                    divider = {},
                ) {
                    categoryTabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTabIndex == index,
                            onClick = { selectedTabIndex = index },
                            text = {
                                Text(
                                    title,
                                    color = if (selectedTabIndex == index) GccGreen else TextMuted,
                                )
                            },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Filtered badge grid
                val filteredBadges = if (selectedTabIndex == 0) {
                    uiState.badges
                } else {
                    val category = BadgeCategory.entries[selectedTabIndex - 1]
                    uiState.badges.filter { it.category == category }
                }.sortedWith(
                    compareByDescending<com.gcc.shared.model.Badge> { it.isUnlocked }
                        .thenBy { it.tier.ordinal }
                )

                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 100.dp),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filteredBadges, key = { it.id }) { badge ->
                        BadgeCard(badge = badge)
                    }
                }
            }
        }
    }
}

private fun BadgeCategory.displayName(): String = when (this) {
    BadgeCategory.JOBS -> "Jobs"
    BadgeCategory.EARNINGS -> "Earnings"
    BadgeCategory.STREAK -> "Streak"
    BadgeCategory.COMMUNITY -> "Community"
    BadgeCategory.EARLY_ADOPTER -> "Early Adopter"
    BadgeCategory.OBSERVE -> "Observer"
    BadgeCategory.VOLUNTEER -> "Volunteer"
    BadgeCategory.FIRST_RESPONDER -> "First Responder"
    BadgeCategory.SPONSOR -> "Sponsor"
}
