package com.gcc.app.ui.gamification

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.gcc.app.ui.components.GccLogo
import com.gcc.app.ui.components.QrCodeCanvas
import com.gcc.app.ui.theme.*
import com.gcc.shared.model.Badge
import com.gcc.shared.model.EarlyAdopterTier
import com.gcc.shared.model.Worker
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt

/**
 * Shareable profile card optimized for social media virality.
 *
 * Differs from [ProfileCard] in key privacy-safe ways:
 * - Shows display handle or "FirstName L." instead of full name
 * - Shows streak column instead of earnings
 * - Includes member ranks (national, state, neighborhood)
 * - Includes member number and "Since Month Year"
 * - Excludes email, phone, street address, zip, dollar earnings, worker ID
 *
 * Uses Fibonacci-based spacing (3, 5, 8, 13, 21, 34, 55, 89dp) and
 * golden ratio (phi) dimensions. Card width = 324dp (200 * phi).
 * Aspect ratio 9:16 for social media sharing.
 */
@Composable
fun ShareableProfileCard(
    worker: Worker,
    badges: List<Badge>,
    streakDays: Int = 0,
    onShareClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Dynamic gradient based on tier
    val gradientColors = when (worker.earlyAdopterTier) {
        EarlyAdopterTier.FOUNDER -> listOf(
            Color(0xFFFFD700), // Gold
            Color(0xFFDAA520), // Goldenrod
            Color(0xFF8B6508), // Dark gold
        )
        EarlyAdopterTier.PIONEER -> listOf(
            Color(0xFFE5E4E2), // Platinum
            Color(0xFFB0B0B0), // Medium gray
            Color(0xFF606060), // Dark gray
        )
        EarlyAdopterTier.EARLY -> listOf(
            Color(0xFFC0C0C0), // Silver
            Color(0xFF909090), // Medium silver
            Color(0xFF505050), // Dark silver
        )
        EarlyAdopterTier.STANDARD -> listOf(
            GccGreen,
            GccGreenDark,
            Color(0xFF0F4C2A),
        )
    }

    // Impact score = jobs * (rating/5.0) * (1 + streak/10.0)
    val rating = worker.stats.averageRating ?: 0.0
    val impactScore = (worker.stats.jobsCompleted * (rating / 5.0) * (1.0 + streakDays / 10.0))
        .roundToInt()

    // Display handle: @handle if set, else "FirstName L." format
    val displayName = if (!worker.displayHandle.isNullOrBlank()) {
        "@${worker.displayHandle}"
    } else {
        val parts = worker.name.split(" ").filter { it.isNotBlank() }
        val firstName = parts.firstOrNull() ?: ""
        val lastInitial = parts.getOrNull(1)?.firstOrNull()?.uppercase() ?: ""
        if (lastInitial.isNotEmpty()) "$firstName $lastInitial." else firstName
    }

    // "Since Month Year" from createdAt
    val localDate = worker.createdAt.toLocalDateTime(TimeZone.currentSystemDefault())
    val monthName = localDate.month.name.lowercase().replaceFirstChar { it.uppercase() }
    val sinceText = "Since $monthName ${localDate.year}"

    // Member ranks text
    val ranksText = buildString {
        append("#${worker.signupRankings.global} nationally")
        append(" \u00B7 ") // middle dot
        append("#${worker.signupRankings.state} in ${worker.address.state}")
        worker.address.neighborhood?.let { neighborhood ->
            append(" \u00B7 ")
            append("#${worker.signupRankings.neighborhood} in $neighborhood")
        }
    }

    Card(
        modifier = modifier
            .widthIn(max = 324.dp) // 200 * phi
            .fillMaxWidth()
            .aspectRatio(9f / 16f),
        shape = RoundedCornerShape(21.dp), // Fibonacci
        elevation = CardDefaults.cardElevation(defaultElevation = GccTheme.elevation.overlay),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(colors = gradientColors)
                )
                .padding(21.dp), // Fibonacci internal padding
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // ── 1. Header: Logo + Share ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GccLogo(size = 34.dp) // Fibonacci
                    ShareButton(
                        onClick = onShareClick,
                        text = "Share",
                    )
                }

                Spacer(modifier = Modifier.height(21.dp)) // Fibonacci

                // ── 2. Avatar circle with initials ──
                Box(
                    modifier = Modifier
                        .size(89.dp) // Fibonacci
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = worker.name.split(" ")
                            .take(2)
                            .joinToString("") { it.first().uppercase() },
                        fontSize = 34.sp, // Fibonacci
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }

                Spacer(modifier = Modifier.height(8.dp)) // Fibonacci sm

                // ── 3. Display handle or "FirstName L." ──
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )

                // ── 4. Member ranks row ──
                Text(
                    text = ranksText,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 5.dp), // Fibonacci
                )

                Spacer(modifier = Modifier.height(3.dp)) // Fibonacci xxs

                // ── 5. "Since Month Year" ──
                Text(
                    text = sinceText,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(13.dp)) // Fibonacci

                // ── 6. Impact score ──
                Text(
                    text = "$impactScore",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "IMPACT SCORE",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    letterSpacing = 2.sp,
                )

                Spacer(modifier = Modifier.height(13.dp)) // Fibonacci

                // ── 7. Stats row: JOBS | STREAK (no earnings) ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(13.dp)) // Fibonacci
                        .background(Color.White.copy(alpha = 0.15f))
                        .padding(13.dp), // Fibonacci
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ShareableStatColumn(
                        value = worker.stats.jobsCompleted,
                        label = "JOBS",
                        delayMs = 0,
                    )
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(55.dp) // Fibonacci internal height
                            .background(Color.White.copy(alpha = 0.3f))
                    )
                    ShareableStatColumn(
                        value = streakDays,
                        label = "\uD83D\uDD25 STREAK",
                        delayMs = 200,
                    )
                }

                Spacer(modifier = Modifier.height(13.dp)) // Fibonacci

                // ── 8. TierBadge + Member number ──
                TierBadge(tier = worker.earlyAdopterTier)

                Spacer(modifier = Modifier.height(5.dp)) // Fibonacci

                Text(
                    text = "Member #${worker.memberNumber}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    letterSpacing = 1.sp,
                )

                Spacer(modifier = Modifier.height(8.dp)) // Fibonacci sm

                // ── 9. Badge showcase row: top 5 unlocked badges ──
                val unlockedBadges = badges.filter { it.isUnlocked }.take(5)
                if (unlockedBadges.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        unlockedBadges.forEachIndexed { index, badge ->
                            if (index > 0) {
                                Spacer(modifier = Modifier.width(8.dp)) // Fibonacci sm
                            }
                            Box(
                                modifier = Modifier
                                    .size(21.dp) // Fibonacci circle size
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.2f))
                                    .border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = badge.category.toEmoji(),
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                }

                // ── 10. Badge count + streak text ──
                Spacer(modifier = Modifier.height(8.dp)) // Fibonacci sm
                val unlockedCount = badges.count { it.isUnlocked }
                Text(
                    text = "\uD83C\uDFC6 $unlockedCount badges earned",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.9f),
                )
                if (streakDays > 0) {
                    Text(
                        text = "\uD83D\uDD25 $streakDays-day streak",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.9f),
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // ── 11. QR code (referral link) ──
                worker.referralCode?.let { code ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        QrCodeCanvas(
                            data = "https://globalcommunitycleaners.org/refer/$code",
                            size = 89.dp,
                            foregroundColor = Color.White,
                            backgroundColor = Color.Transparent,
                        )
                        Spacer(modifier = Modifier.height(5.dp))
                        // ── 12. Referral code + footer URL ──
                        Text(
                            text = code,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            letterSpacing = 3.sp,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(13.dp)) // Fibonacci

                // Footer
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp), // Fibonacci sm
                    color = Color.White.copy(alpha = 0.3f),
                )
                Text(
                    text = "globalcommunitycleaners.org",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ShareableStatColumn(
    value: Int,
    label: String,
    delayMs: Int,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedCounter(
            targetValue = value,
            durationMs = 800,
            delayMs = delayMs,
            style = MaterialTheme.typography.headlineLarge.copy(
                fontWeight = FontWeight.Bold,
                color = Color.White,
            ),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White.copy(alpha = 0.7f),
        )
    }
}
