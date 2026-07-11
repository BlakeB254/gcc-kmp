package com.gcc.app.ui.screens

import androidx.compose.foundation.background
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
import com.gcc.app.platform.ShareManager
import com.gcc.app.ui.components.*
import com.gcc.app.ui.gamification.AchievementPopup
import com.gcc.app.ui.gamification.AnimatedRating
import com.gcc.app.ui.gamification.BadgeCard
import com.gcc.app.ui.theme.*
import com.gcc.app.util.padTwo
import com.gcc.app.viewmodel.ProfileViewModel
import com.gcc.shared.model.VerificationStatus
import com.gcc.shared.auth.TokenManager
import org.koin.compose.koinInject

@Composable
fun ProfileScreen(
    onBack: () -> Unit = {},
    onAchievementsClick: () -> Unit = {},
    onMapClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onAdminClick: () -> Unit = {},
    onUserCardClick: () -> Unit = {},
    onPointsClick: () -> Unit = {},
    onImpactClick: () -> Unit = {},
    onVerifyDocuments: () -> Unit = {},
) {
    val viewModel = koinInject<ProfileViewModel>()
    val tokenManager = koinInject<TokenManager>()
    val isAdmin = remember { tokenManager.isAdmin() }
    val uiState by viewModel.uiState.collectAsState()
    val shareManager = remember { ShareManager() }

    LaunchedEffect(Unit) { viewModel.loadProfile() }

    // Achievement popup overlay
    uiState.newlyUnlockedBadge?.let { badge ->
        AchievementPopup(
            badge = badge,
            onShare = {
                shareManager.shareText(
                    text = "I just unlocked the \"${badge.name}\" badge on GCC! ${badge.description} #GCC #CleanCity",
                    subject = "GCC Achievement Unlocked",
                )
            },
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

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            GccBottomNavBar(
                selectedTab = if (isAdmin) 3 else 2,
                isAdmin = isAdmin,
                onTabSelected = { tab ->
                    when (tab) {
                        0 -> onBack()
                        1 -> onMapClick()
                        2 -> if (isAdmin) onAdminClick()
                        // Tab 3 (admin Profile) = already here, no-op
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
            // Gradient header section. The background is declared before
            // statusBarsPadding so the gradient still bleeds edge-to-edge behind the
            // status bar, while the Back button and logo below are laid out clear of
            // the clock and Dynamic Island.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(GccGreen, GccGreenDark)
                        )
                    )
                    .statusBarsPadding()
                    .height(200.dp),
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
                    accentColor = CardAccentSky,
                    modifier = Modifier.weight(1f),
                )
            }

            // Verification banner — shown when not approved
            if (worker.verification.status != VerificationStatus.APPROVED) {
                VerificationBanner(
                    status = worker.verification.status,
                    onAction = onVerifyDocuments,
                )
                Spacer(modifier = Modifier.height(8.dp))
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

            // Points & Impact links
            GccButton(
                text = "View Points",
                onClick = onPointsClick,
                variant = GccButtonVariant.OUTLINED,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            Spacer(modifier = Modifier.height(8.dp))

            GccButton(
                text = "Community Impact",
                onClick = onImpactClick,
                variant = GccButtonVariant.OUTLINED,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            Spacer(modifier = Modifier.height(13.dp))

            // Settings button
            GccButton(
                text = "Settings",
                onClick = onSettingsClick,
                variant = GccButtonVariant.OUTLINED,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            Spacer(modifier = Modifier.height(13.dp)) // Fibonacci

            GccButton(
                text = "My Card",
                onClick = onUserCardClick,
                variant = GccButtonVariant.PRIMARY,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun VerificationBanner(
    status: VerificationStatus,
    onAction: () -> Unit,
) {
    if (status == VerificationStatus.APPROVED) return

    val icon: String
    val title: String
    val subtitle: String
    val buttonText: String
    val accentColor: Color

    when (status) {
        VerificationStatus.PENDING -> {
            icon = "\uD83D\uDD12"
            title = "Verify Your Identity"
            subtitle = "Upload your documents to start earning"
            buttonText = "Upload Documents"
            accentColor = GccGreen
        }
        VerificationStatus.REJECTED -> {
            icon = "\u26A0\uFE0F"
            title = "Documents Need Attention"
            subtitle = "Your documents were not accepted — please resubmit"
            buttonText = "Resubmit Documents"
            accentColor = GccError
        }
        VerificationStatus.NEEDS_INFO -> {
            icon = "\u2139\uFE0F"
            title = "More Info Needed"
            subtitle = "We need additional information to verify your account"
            buttonText = "Provide Info"
            accentColor = GccSky
        }
        VerificationStatus.APPROVED -> return
    }

    GccCard(
        accentColor = accentColor,
        modifier = Modifier.padding(horizontal = 16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(icon, style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        GccButton(
            text = buttonText,
            onClick = onAction,
            variant = GccButtonVariant.PRIMARY,
        )
    }
}
