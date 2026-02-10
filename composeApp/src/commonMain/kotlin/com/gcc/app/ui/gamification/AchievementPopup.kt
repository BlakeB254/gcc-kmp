package com.gcc.app.ui.gamification

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gcc.app.ui.theme.*
import com.gcc.shared.model.Badge
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Full-screen achievement unlock popup with layered animations
 * and sacred geometry proportions.
 *
 * Uses Fibonacci-based dimensions:
 * - Badge icon circle: 89dp
 * - Emoji: 34sp
 * - Section spacing: 13, 21, 34dp
 * - Content padding: 34dp
 * - Button row spacing: 13dp
 *
 * Sequence:
 * 1. Dark overlay fades in (300ms)
 * 2. Badge icon scales up with spring animation
 * 3. Canvas particle burst -- ~20 colored circles expand outward
 * 4. Title + description slide in from bottom
 * 5. Share and Continue buttons appear
 */
@Composable
fun AchievementPopup(
    badge: Badge,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showContent by remember { mutableStateOf(false) }
    LaunchedEffect(badge) { showContent = true }

    val tierColor = badge.tier.toColor()

    // Badge scale animation with spring bounce
    val badgeScale by animateFloatAsState(
        targetValue = if (showContent) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "badge_scale",
    )

    // Particle burst animation
    val particleProgress = remember { Animatable(0f) }
    LaunchedEffect(showContent) {
        if (showContent) {
            particleProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 1200, delayMillis = 200),
            )
        }
    }

    // Generate stable particle data once per badge
    val particles = remember(badge) {
        val rng = Random(badge.id.hashCode())
        List(20) {
            ParticleData(
                angle = rng.nextFloat() * 2f * kotlin.math.PI.toFloat(),
                speed = 80f + rng.nextFloat() * 120f,
                radius = 3f + rng.nextFloat() * 5f,
                color = listOf(
                    Color(0xFFFFD700), // Gold
                    Color(0xFF22C55E), // Green
                    Color(0xFFFF6B6B), // Red
                    Color(0xFF4ECDC4), // Teal
                    Color(0xFFFFE66D), // Yellow
                    Color(0xFFFF8A65), // Orange
                    Color(0xFF7C4DFF), // Purple
                ).random(rng),
            )
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        // Dark overlay
        AnimatedVisibility(
            visible = showContent,
            enter = fadeIn(tween(300)),
            exit = fadeOut(tween(300)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f))
            )
        }

        // Particle burst canvas
        if (particleProgress.value > 0f) {
            Canvas(
                modifier = Modifier
                    .size(324.dp) // 200 * phi -- burst area
            ) {
                val centerX = size.width / 2f
                val centerY = size.height / 2f
                val progress = particleProgress.value
                val alphaFade = 1f - progress

                particles.forEach { p ->
                    val dist = p.speed * progress
                    val px = centerX + cos(p.angle) * dist
                    val py = centerY + sin(p.angle) * dist

                    drawCircle(
                        color = p.color.copy(alpha = alphaFade * 0.8f),
                        radius = p.radius * (1f - progress * 0.5f),
                        center = Offset(px, py),
                    )
                }
            }
        }

        // Content
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(34.dp), // Fibonacci (from 32dp)
        ) {
            // Badge icon -- 89dp (Fibonacci, from 96dp)
            Box(
                modifier = Modifier
                    .scale(badgeScale)
                    .size(89.dp) // Fibonacci
                    .clip(CircleShape)
                    .background(tierColor.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = badge.category.toEmoji(),
                    fontSize = 34.sp, // Fibonacci (from 48sp)
                )
            }

            Spacer(modifier = Modifier.height(21.dp)) // Fibonacci (from 24dp)

            // Title
            AnimatedVisibility(
                visible = showContent,
                enter = slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(500, delayMillis = 400),
                ) + fadeIn(tween(500, delayMillis = 400)),
            ) {
                Text(
                    text = "Achievement Unlocked!",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(modifier = Modifier.height(13.dp)) // Fibonacci (from 8dp)

            // Badge name
            AnimatedVisibility(
                visible = showContent,
                enter = fadeIn(tween(400, delayMillis = 600)),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = badge.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = tierColor,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(5.dp)) // Fibonacci xs
                    Text(
                        text = badge.description,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(5.dp)) // Fibonacci xs
                    Text(
                        text = badge.tier.name,
                        style = MaterialTheme.typography.labelLarge,
                        color = tierColor,
                    )
                }
            }

            Spacer(modifier = Modifier.height(34.dp)) // Fibonacci (from 32dp)

            // Buttons
            AnimatedVisibility(
                visible = showContent,
                enter = fadeIn(tween(300, delayMillis = 800)),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(13.dp), // Fibonacci (from 16dp)
                ) {
                    OutlinedButton(
                        onClick = onShare,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color.White,
                        ),
                    ) {
                        Text("Share")
                    }
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = tierColor,
                        ),
                    ) {
                        Text("Continue")
                    }
                }
            }
        }
    }
}

/**
 * Data for a single particle in the unlock burst animation.
 */
private data class ParticleData(
    val angle: Float,
    val speed: Float,
    val radius: Float,
    val color: Color,
)

/**
 * Extension to pick a random element from a list using a specific Random instance.
 */
private fun <T> List<T>.random(rng: Random): T = this[rng.nextInt(size)]
