package com.gcc.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
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
import com.gcc.app.ui.components.GccButton
import com.gcc.app.ui.components.GccButtonVariant
import com.gcc.app.ui.components.GccLogo
import com.gcc.app.ui.theme.*
import kotlinx.coroutines.launch

private data class OnboardingPage(
    val title: String,
    val subtitle: String,
    val emoji: String,
)

private val onboardingPages = listOf(
    OnboardingPage(
        title = "Clean Your Community",
        subtitle = "Accept routes, complete checkpoints, earn rewards for keeping Chicago clean.",
        emoji = "\uD83E\uDDF9",
    ),
    OnboardingPage(
        title = "Track Your Impact",
        subtitle = "GPS-verified cleanups, photo documentation, and real-time progress tracking.",
        emoji = "\uD83D\uDCCD",
    ),
    OnboardingPage(
        title = "Get Paid & Level Up",
        subtitle = "Weekly payouts, achievement badges, leaderboards, and early adopter tiers.",
        emoji = "\uD83C\uDFC6",
    ),
)

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit = {},
) {
    val pagerState = rememberPagerState(pageCount = { onboardingPages.size })
    val coroutineScope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(GccGreen, GccGreenDark, Color(0xFF0F4C2A))
                )
            ),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            GccLogo(size = 48.dp)

            Spacer(modifier = Modifier.height(24.dp))

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
            ) { page ->
                val pageData = onboardingPages[page]
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        pageData.emoji,
                        fontSize = 72.sp,
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    Text(
                        pageData.title,
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold,
                        ),
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        pageData.subtitle,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center,
                    )
                }
            }

            // Dot indicators
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                repeat(onboardingPages.size) { index ->
                    val isActive = index == pagerState.currentPage
                    val color by animateColorAsState(
                        targetValue = if (isActive) Color.White else Color.White.copy(alpha = 0.4f),
                    )
                    Box(
                        modifier = Modifier
                            .size(if (isActive) 10.dp else 8.dp)
                            .clip(CircleShape)
                            .background(color),
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Button
            val isLastPage = pagerState.currentPage == onboardingPages.size - 1
            GccButton(
                text = if (isLastPage) "Get Started" else "Next",
                onClick = {
                    if (isLastPage) {
                        onComplete()
                    } else {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    }
                },
                variant = GccButtonVariant.SECONDARY,
                modifier = Modifier.padding(horizontal = 32.dp),
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Skip button on non-last pages
            if (!isLastPage) {
                TextButton(onClick = onComplete) {
                    Text(
                        "Skip",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
