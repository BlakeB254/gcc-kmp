package com.gcc.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

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
    val art: IntroArtKind,
)

/** Which vector illustration the slide's art slot draws (see [IntroSlideArt]). */
internal enum class IntroArtKind { SCAN, CLAIM, EARN, IMPACT }

internal val introPages = listOf(
    IntroPage(
        title = "Scan your block",
        body = "Walk your block with the camera on. GCC spots litter, maps it, and verifies the cleanup — no paperwork.",
        artColors = listOf(GccGreen, GccGreenDark),
        art = IntroArtKind.SCAN,
    ),
    IntroPage(
        title = "Claim your block",
        body = "Pick a route near you and make it yours. Clean it, keep it clean, and get recognized for it.",
        primingNote = "GCC uses your location to match you with nearby blocks and your camera to scan them. We'll ask for each permission right when it's needed.",
        artColors = listOf(Color(0xFF1E8A5A), Color(0xFF0F4C2A)),
        art = IntroArtKind.CLAIM,
    ),
    IntroPage(
        title = "Earn",
        body = "Verified cleanups pay out the same day. Every scan earns points, badges, and a spot on the leaderboard.",
        artColors = listOf(Color(0xFF2E9E57), Color(0xFF14532D)),
        art = IntroArtKind.EARN,
    ),
    IntroPage(
        title = "See the change",
        body = "Watch your neighborhood get cleaner, block by block, on the community impact map.",
        artColors = listOf(Color(0xFF17806B), Color(0xFF0B3D22)),
        art = IntroArtKind.IMPACT,
    ),
)

/**
 * Art slot for an intro slide.
 *
 * When a [Painter] is supplied (e.g. `painterResource(...)` — the same asset
 * feeds the store preview cards) it fills the panel. Otherwise a
 * brand-vector illustration is drawn for [IntroPage.art] over the slide's
 * gradient — deterministic, crisp at any density, and zero asset weight.
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
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawIntroArt(page.art, size.width, size.height)
            }
        }
    }
}

// ── Vector slide illustrations ──
// Shared visual language with the splash: grey = dirty/unknown, GccGreen =
// cleaned, white strokes = structure, soft glows for focus points.
private val ArtGrey = Color(0xFF4A4A4A)
private val ArtRed = Color(0xFFE53935)
private val ArtWhite = Color.White

/** Map-grid street block helper used by SCAN / CLAIM / IMPACT. */
private fun DrawScope.drawBlockGrid(
    cx: Float, cy: Float, blockW: Float, blockH: Float, cols: Int, rows: Int,
    blockAt: (col: Int, row: Int) -> Color,
) {
    val gap = blockW * 0.12f
    val totalW = cols * blockW + (cols - 1) * gap
    val totalH = rows * blockH + (rows - 1) * gap
    val left = cx - totalW / 2f
    val top = cy - totalH / 2f
    for (r in 0 until rows) {
        for (c in 0 until cols) {
            drawRoundRect(
                color = blockAt(c, r),
                topLeft = Offset(left + c * (blockW + gap), top + r * (blockH + gap)),
                size = androidx.compose.ui.geometry.Size(blockW, blockH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(blockW * 0.12f),
            )
        }
    }
}

private fun DrawScope.drawIntroArt(kind: IntroArtKind, w: Float, h: Float) {
    val cx = w / 2f
    val cy = h / 2f
    val u = minOf(w, h) // unit scale

    when (kind) {
        IntroArtKind.SCAN -> {
            // Phone frame
            val phoneW = u * 0.34f
            val phoneH = phoneW * 1.9f
            drawRoundRect(
                color = ArtWhite.copy(alpha = 0.14f),
                topLeft = Offset(cx - phoneW / 2f, cy - phoneH / 2f),
                size = androidx.compose.ui.geometry.Size(phoneW, phoneH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(u * 0.05f),
            )
            drawRoundRect(
                color = ArtWhite.copy(alpha = 0.9f),
                topLeft = Offset(cx - phoneW / 2f, cy - phoneH / 2f),
                size = androidx.compose.ui.geometry.Size(phoneW, phoneH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(u * 0.05f),
                style = Stroke(width = u * 0.012f),
            )
            // Viewfinder brackets
            val vfW = phoneW * 0.62f
            val vfH = vfW * 0.8f
            val vfLeft = cx - vfW / 2f
            val vfTop = cy - vfH / 2f
            val arm = vfW * 0.22f
            val bracketStroke = Stroke(width = u * 0.012f)
            val bracket = ArtWhite
            // top-left
            drawLine(bracket, Offset(vfLeft, vfTop + arm), Offset(vfLeft, vfTop), bracketStroke.width)
            drawLine(bracket, Offset(vfLeft, vfTop), Offset(vfLeft + arm, vfTop), bracketStroke.width)
            // top-right
            drawLine(bracket, Offset(vfLeft + vfW - arm, vfTop), Offset(vfLeft + vfW, vfTop), bracketStroke.width)
            drawLine(bracket, Offset(vfLeft + vfW, vfTop), Offset(vfLeft + vfW, vfTop + arm), bracketStroke.width)
            // bottom-left
            drawLine(bracket, Offset(vfLeft, vfTop + vfH - arm), Offset(vfLeft, vfTop + vfH), bracketStroke.width)
            drawLine(bracket, Offset(vfLeft, vfTop + vfH), Offset(vfLeft + arm, vfTop + vfH), bracketStroke.width)
            // bottom-right
            drawLine(bracket, Offset(vfLeft + vfW - arm, vfTop + vfH), Offset(vfLeft + vfW, vfTop + vfH), bracketStroke.width)
            drawLine(bracket, Offset(vfLeft + vfW, vfTop + vfH), Offset(vfLeft + vfW, vfTop + vfH - arm), bracketStroke.width)
            // Detected litter dots inside the viewfinder: red -> green
            drawCircle(ArtRed, u * 0.018f, Offset(cx - vfW * 0.18f, cy - vfH * 0.12f))
            drawCircle(GccGreen, u * 0.018f, Offset(cx + vfW * 0.15f, cy + vfH * 0.10f))
            // Scan line
            drawLine(
                GccGreen.copy(alpha = 0.9f),
                Offset(vfLeft + arm * 0.4f, cy),
                Offset(vfLeft + vfW - arm * 0.4f, cy),
                u * 0.008f,
            )
        }

        IntroArtKind.CLAIM -> {
            // Block grid; the claimed block glows green with a check pin
            drawBlockGrid(cx, cy, u * 0.16f, u * 0.11f, 3, 3) { c, r ->
                if (c == 1 && r == 1) GccGreen else ArtGrey.copy(alpha = 0.55f)
            }
            // Check circle over the claimed block
            val badgeR = u * 0.075f
            val badgeCenter = Offset(cx + u * 0.10f, cy - u * 0.10f)
            drawCircle(GccGreen, badgeR, badgeCenter)
            drawCircle(ArtWhite, badgeR, badgeCenter, style = Stroke(width = u * 0.008f))
            val checkStroke = Stroke(width = u * 0.014f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(ArtWhite, badgeCenter + Offset(-badgeR * 0.45f, 0f), badgeCenter + Offset(-badgeR * 0.1f, badgeR * 0.35f), checkStroke.width, cap = checkStroke.cap)
            drawLine(ArtWhite, badgeCenter + Offset(-badgeR * 0.1f, badgeR * 0.35f), badgeCenter + Offset(badgeR * 0.5f, -badgeR * 0.35f), checkStroke.width, cap = checkStroke.cap)
        }

        IntroArtKind.EARN -> {
            // Coin with a bold up-arrow (earnings growth) + sparkle ticks
            val coinR = u * 0.17f
            drawCircle(GccGreen.copy(alpha = 0.35f), coinR * 1.5f, Offset(cx, cy))
            drawCircle(GccGreen, coinR, Offset(cx, cy))
            drawCircle(ArtWhite.copy(alpha = 0.85f), coinR, Offset(cx, cy), style = Stroke(width = u * 0.01f))
            drawCircle(GccGreenDark, coinR * 0.72f, Offset(cx, cy))
            val arrowStroke = u * 0.022f
            val cap = androidx.compose.ui.graphics.StrokeCap.Round
            // shaft
            drawLine(ArtWhite, Offset(cx, cy + coinR * 0.38f), Offset(cx, cy - coinR * 0.30f), arrowStroke, cap = cap)
            // head
            drawLine(ArtWhite, Offset(cx, cy - coinR * 0.30f), Offset(cx - coinR * 0.24f, cy - coinR * 0.04f), arrowStroke, cap = cap)
            drawLine(ArtWhite, Offset(cx, cy - coinR * 0.30f), Offset(cx + coinR * 0.24f, cy - coinR * 0.04f), arrowStroke, cap = cap)
            // Sparkle ticks around the coin
            for (i in 0 until 8) {
                val angle = (2f * PI.toFloat() / 8f) * i + 0.2f
                val inner = coinR * 1.25f
                val outer = coinR * (1.38f + 0.1f * (i % 2))
                drawLine(
                    ArtWhite.copy(alpha = 0.7f),
                    Offset(cx + cos(angle) * inner, cy + sin(angle) * inner),
                    Offset(cx + cos(angle) * outer, cy + sin(angle) * outer),
                    u * 0.008f,
                )
            }
        }

        IntroArtKind.IMPACT -> {
            // Before/after: left blocks grey with red dots, right blocks green,
            // with an arrow across the seam — the neighborhood flipping clean.
            drawBlockGrid(cx, cy, u * 0.17f, u * 0.125f, 4, 3) { c, _ ->
                if (c < 2) ArtGrey.copy(alpha = 0.55f) else GccGreen.copy(alpha = 0.9f)
            }
            // Red litter dots on the dirty side
            val dotR = u * 0.016f
            drawCircle(ArtRed, dotR, Offset(cx - u * 0.30f, cy - u * 0.07f))
            drawCircle(ArtRed, dotR, Offset(cx - u * 0.20f, cy + u * 0.10f))
            drawCircle(ArtRed, dotR, Offset(cx - u * 0.34f, cy + u * 0.04f))
            // Arrow riding the seam between the dirty and clean halves
            val arrowStroke = Stroke(width = u * 0.014f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(ArtWhite, Offset(cx - u * 0.11f, cy), Offset(cx + u * 0.11f, cy), arrowStroke.width, cap = arrowStroke.cap)
            drawLine(ArtWhite, Offset(cx + u * 0.05f, cy - u * 0.04f), Offset(cx + u * 0.11f, cy), arrowStroke.width, cap = arrowStroke.cap)
            drawLine(ArtWhite, Offset(cx + u * 0.05f, cy + u * 0.04f), Offset(cx + u * 0.11f, cy), arrowStroke.width, cap = arrowStroke.cap)
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
