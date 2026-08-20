package com.gcc.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.components.GccButton
import com.gcc.app.ui.components.GccButtonVariant
import com.gcc.app.ui.components.GccLogo
import com.gcc.app.ui.theme.*
import kotlinx.coroutines.launch

/**
 * One intro carousel page. Shared by the pre-auth [IntroScreen] (§3) and the
 * post-registration [OnboardingScreen] so there is a single carousel
 * implementation.
 */
internal data class IntroPage(
    val title: String,
    val body: String,
    /** Optional one-line permission-priming note shown under the body (copy
     *  only — no system prompt is triggered from the carousel). */
    val primingNote: String? = null,
    val artColors: List<Color>,
)

internal val introPages = listOf(
    IntroPage(
        title = "Scan your block",
        body = "Walk your block with the camera on. GCC spots litter, maps it, and verifies the cleanup — no paperwork.",
        artColors = listOf(GccGreen, GccGreenDark),
    ),
    IntroPage(
        title = "Claim your block",
        body = "Pick a route near you and make it yours. Clean it, keep it clean, and get recognized for it.",
        primingNote = "GCC uses your location to match you with nearby blocks and your camera to scan them. We'll ask for each permission right when it's needed.",
        artColors = listOf(Color(0xFF1E8A5A), Color(0xFF0F4C2A)),
    ),
    IntroPage(
        title = "Earn",
        body = "Verified cleanups pay out the same day. Every scan earns points, badges, and a spot on the leaderboard.",
        artColors = listOf(Color(0xFF2E9E57), Color(0xFF14532D)),
    ),
    IntroPage(
        title = "See the change",
        body = "Watch your neighborhood get cleaner, block by block, on the community impact map.",
        artColors = listOf(Color(0xFF17806B), Color(0xFF0B3D22)),
    ),
)

/**
 * Art slot for an intro slide.
 *
 * Placeholder today: a brand-green gradient panel. When the §6 background
 * assets land, pass a [Painter] per slide (e.g. `painterResource(...)`) and
 * the gradient is replaced with no layout changes — the same asset feeds the
 * store preview cards.
 */
@Composable
internal fun IntroSlideArt(
    page: IntroPage,
    artPainter: Painter? = null,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.clip(RoundedCornerShape(24.dp))) {
        if (artPainter != null) {
            Image(
                painter = artPainter,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.linearGradient(page.artColors)),
            )
        }
    }
}

@Composable
internal fun IntroSlideContent(
    page: IntroPage,
    artPainter: Painter? = null,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IntroSlideArt(
            page = page,
            artPainter = artPainter,
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp),
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            page.title,
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold,
            ),
            color = Color.White,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            page.body,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.85f),
            textAlign = TextAlign.Center,
        )

        page.primingNote?.let { note ->
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.65f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The shared carousel: pager + dots + Next/complete button, with Skip
 * top-right and an optional back button top-left (post-registration flow).
 *
 * [artPainters] maps 1:1 onto [introPages]; missing/null entries keep the
 * gradient placeholder.
 */
@Composable
internal fun IntroCarousel(
    onComplete: () -> Unit,
    completeLabel: String,
    onBack: (() -> Unit)? = null,
    artPainters: List<Painter?> = emptyList(),
) {
    val pagerState = rememberPagerState(pageCount = { introPages.size })
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
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Top bar: optional back (left) + Skip (right)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    TextButton(onClick = onBack) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.9f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("←", style = MaterialTheme.typography.titleMedium, color = GccGreenDark)
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.width(52.dp))
                }
                TextButton(onClick = onComplete) {
                    Text(
                        "Skip",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            GccLogo(size = 48.dp)

            Spacer(modifier = Modifier.height(24.dp))

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
            ) { page ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    IntroSlideContent(
                        page = introPages[page],
                        artPainter = artPainters.getOrNull(page),
                    )
                }
            }

            // Dot indicators
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                repeat(introPages.size) { index ->
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
            val isLastPage = pagerState.currentPage == introPages.size - 1
            GccButton(
                text = if (isLastPage) completeLabel else "Next",
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

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/**
 * First-run (pre-auth) intro carousel (§3): four slides, one per core
 * offering — Scan / Claim your block / Earn / See the change. Skip
 * (top-right) and the final "Get started" CTA both complete via
 * [onComplete] (RootComponent marks intro_seen and routes onward).
 */
@Composable
fun IntroScreen(onComplete: () -> Unit = {}) {
    IntroCarousel(
        onComplete = onComplete,
        completeLabel = "Get started",
    )
}
