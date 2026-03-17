package com.gcc.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcc.app.platform.ShareManager
import com.gcc.app.ui.components.GccButton
import com.gcc.app.ui.components.GccButtonVariant
import com.gcc.app.ui.gamification.ShareableProfileCard
import com.gcc.app.ui.theme.GccGreen
import com.gcc.app.viewmodel.ProfileViewModel
import org.koin.compose.koinInject

@Composable
fun UserCardScreen(
    onBack: () -> Unit = {},
) {
    val viewModel = koinInject<ProfileViewModel>()
    val shareManager = koinInject<ShareManager>()
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) { viewModel.loadProfile() }

    val worker = uiState.worker

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A)),
    ) {
        // Close button top-left
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .statusBarsPadding(),
        ) {
            Text(
                "\u2715",
                color = Color.White.copy(alpha = 0.6f),
                fontWeight = FontWeight.Bold,
            )
        }

        if (worker != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 21.dp, vertical = 55.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                ShareableProfileCard(
                    worker = worker,
                    badges = uiState.badges,
                    streakDays = uiState.streakDays,
                    modifier = Modifier.weight(1f, fill = false),
                )

                Spacer(modifier = Modifier.height(21.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(13.dp),
                ) {
                    GccButton(
                        text = "Save to Photos",
                        onClick = { /* TODO: bitmap capture in future task */ },
                        variant = GccButtonVariant.OUTLINED,
                        modifier = Modifier.weight(1f),
                    )
                    GccButton(
                        text = "Share",
                        onClick = {
                            val handle = worker.displayHandle?.let { "@$it" }
                                ?: worker.name.split(" ").first()
                            val badgeCount = uiState.badges.count { it.isUnlocked }
                            shareManager.shareText(
                                text = "$handle \u00b7 $badgeCount badges earned \u00b7 " +
                                    "Member #${worker.signupRankings.global} \u00b7 " +
                                    "Join the movement: https://globalcommunitycleaners.org" +
                                    (worker.referralCode?.let { "/refer/$it" } ?: ""),
                                subject = "My GCC Card",
                            )
                        },
                        variant = GccButtonVariant.PRIMARY,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = GccGreen)
            }
        }
    }
}
