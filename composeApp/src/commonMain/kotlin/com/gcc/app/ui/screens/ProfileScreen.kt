package com.gcc.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gcc.app.ui.components.*
import com.gcc.app.ui.gamification.AchievementPopup
import com.gcc.app.ui.gamification.AnimatedRating
import com.gcc.app.ui.gamification.BadgeCard
import com.gcc.app.ui.gamification.ProfileCard
import com.gcc.app.ui.theme.*
import com.gcc.app.util.padTwo
import com.gcc.app.platform.ShareManager
import com.gcc.app.viewmodel.ProfileViewModel
import com.gcc.shared.auth.TokenManager
import org.koin.compose.koinInject

@Composable
fun ProfileScreen(
    onBack: () -> Unit = {},
    onAchievementsClick: () -> Unit = {},
    onMapClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onAdminClick: () -> Unit = {},
) {
    val viewModel = koinInject<ProfileViewModel>()
    val shareManager = koinInject<ShareManager>()
    val tokenManager = koinInject<TokenManager>()
    val isAdmin = remember { tokenManager.isAdmin() }
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) { viewModel.loadProfile() }

    // Share card overlay state
    var showShareOverlay by remember { mutableStateOf(false) }

    // Achievement popup overlay
    uiState.newlyUnlockedBadge?.let { badge ->
        AchievementPopup(
            badge = badge,
            onShare = {},
            onDismiss = { viewModel.dismissUnlockedBadge() },
        )
    }

    if (uiState.isLoading) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = GccGreen)
        }
        return
    }

    val worker = uiState.worker
    if (worker == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Could not load profile")
                uiState.error?.let { error ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(error, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(modifier = Modifier.height(16.dp))
                GccButton(
                    text = "Retry",
                    onClick = { viewModel.loadProfile() },
                    variant = GccButtonVariant.PRIMARY,
                )
            }
        }
        return
    }

    // Share card fullscreen overlay
    if (showShareOverlay) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.8f))
                .clickable(onClick = { showShareOverlay = false }),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ProfileCard(
                    worker = worker,
                    badges = uiState.badges,
                    streakDays = uiState.streakDays,
                    onShareClick = {
                        val badgeCount = uiState.badges.count { it.isUnlocked }
                        shareManager.shareText(
                            text = "I'm ${worker.name} on Global Community Cleaners! " +
                                "${worker.stats.jobsCompleted} jobs completed, " +
                                "$badgeCount badges earned. " +
                                "Join the movement: https://globalcommunitycleaners.org" +
                                (worker.referralCode?.let { "/refer/$it" } ?: ""),
                            subject = "My GCC Profile",
                        )
                    },
                )

                Spacer(modifier = Modifier.height(21.dp)) // Fibonacci

                GccButton(
                    text = "Dismiss",
                    onClick = { showShareOverlay = false },
                    variant = GccButtonVariant.OUTLINED,
                )
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            val profileTab = if (isAdmin) 3 else 2
            GccBottomNavBar(
                selectedTab = profileTab,
                isAdmin = isAdmin,
                onTabSelected = { tab ->
                    when (tab) {
                        0 -> onBack()
                        1 -> onMapClick()
                        2 -> if (isAdmin) onAdminClick()
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // Gradient header section
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(GccGreen, GccGreenDark)
                        )
                    ),
            ) {
                // Back button
                TextButton(
                    onClick = onBack,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp),
                ) {
                    Text("\u2190 Back", color = Color.White)
                }

                // Logo top-right
                GccLogo(
                    size = 36.dp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp),
                )

                // Avatar + name
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(top = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Avatar circle with initials
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = worker.name.split(" ")
                                .take(2)
                                .joinToString("") { it.first().uppercase() },
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        worker.name,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                    )

                    AnimatedRating(
                        rating = worker.stats.averageRating,
                        style = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                    )
                }
            }

            // Stats row overlapping header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .offset(y = (-30).dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val totalEarnings = worker.stats.totalEarnings
                val dollars = totalEarnings / 100
                val cents = totalEarnings % 100
                StatCard(
                    value = "$$dollars.${cents.padTwo()}",
                    label = "Earned",
                    accentColor = CardAccentGreen,
                    modifier = Modifier.weight(1f),
                )
                StatCard(
                    value = "${worker.stats.jobsCompleted}",
                    label = "Jobs",
                    accentColor = CardAccentBlue,
                    modifier = Modifier.weight(1f),
                )
                StatCard(
                    value = "${uiState.streakDays}",
                    label = "Streak",
                    accentColor = CardAccentGold,
                    modifier = Modifier.weight(1f),
                )
            }

            // Badge preview
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                SectionHeader(
                    title = "Your Badges",
                    actionText = "View All",
                    onAction = onAchievementsClick,
                )
                Spacer(modifier = Modifier.height(8.dp))

                val previewBadges = uiState.badges
                    .sortedByDescending { it.isUnlocked }
                    .take(4)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    previewBadges.forEach { badge ->
                        BadgeCard(
                            badge = badge,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Settings button
            GccButton(
                text = "Settings",
                onClick = onSettingsClick,
                variant = GccButtonVariant.OUTLINED,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            Spacer(modifier = Modifier.height(13.dp)) // Fibonacci

            // Share Card button
            GccButton(
                text = "Share Card",
                onClick = { showShareOverlay = true },
                variant = GccButtonVariant.PRIMARY,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
