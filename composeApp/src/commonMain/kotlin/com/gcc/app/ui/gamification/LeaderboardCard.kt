package com.gcc.app.ui.gamification

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
import com.gcc.app.ui.theme.*
import com.gcc.shared.model.LeaderboardEntry

/**
 * Shareable leaderboard card in 9:16 format with sacred geometry proportions.
 *
 * Uses Fibonacci-based spacing and golden ratio dimensions.
 * Card width = 324dp (200 * phi), corner radius = 21dp.
 * Features a mini podium for top 3, current user highlight, and green gradient.
 */
@Composable
fun LeaderboardCard(
    topEntries: List<LeaderboardEntry>,  // Top 3 for podium
    currentUser: LeaderboardEntry?,
    modifier: Modifier = Modifier,
) {
    val gradientColors = listOf(
        GccGreen,
        GccGreenDark,
        Color(0xFF0F4C2A),
    )

    Card(
        modifier = modifier
            .width(324.dp) // 200 * phi
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
                // Header: Logo
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GccLogo(size = 34.dp) // Fibonacci
                    Spacer(modifier = Modifier.width(8.dp))
                }

                Spacer(modifier = Modifier.height(21.dp)) // Fibonacci

                // Title with crown
                Text(
                    text = "\uD83D\uDC51 GCC Top Cleaners",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(34.dp)) // Fibonacci

                // Mini podium: #2 left, #1 center (elevated), #3 right
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    // #2 - Left (Silver)
                    if (topEntries.size >= 2) {
                        PodiumEntry(
                            entry = topEntries[1],
                            avatarSize = 34, // Fibonacci
                            medalColor = BadgeSilver,
                            medalLabel = "2",
                            pedestal = 55, // Fibonacci
                        )
                    } else {
                        Spacer(modifier = Modifier.width(55.dp))
                    }

                    // #1 - Center (Gold, tallest)
                    if (topEntries.isNotEmpty()) {
                        PodiumEntry(
                            entry = topEntries[0],
                            avatarSize = 55, // Fibonacci
                            medalColor = BadgeGold,
                            medalLabel = "1",
                            pedestal = 89, // Fibonacci
                        )
                    }

                    // #3 - Right (Bronze)
                    if (topEntries.size >= 3) {
                        PodiumEntry(
                            entry = topEntries[2],
                            avatarSize = 34, // Fibonacci
                            medalColor = BadgeBronze,
                            medalLabel = "3",
                            pedestal = 34, // Fibonacci
                        )
                    } else {
                        Spacer(modifier = Modifier.width(55.dp))
                    }
                }

                Spacer(modifier = Modifier.height(34.dp)) // Fibonacci

                // Current user highlighted row
                currentUser?.let { user ->
                    HorizontalDivider(
                        color = Color.White.copy(alpha = 0.3f),
                        modifier = Modifier.padding(vertical = 13.dp), // Fibonacci
                    )
                    CurrentUserRow(entry = user)
                }

                Spacer(modifier = Modifier.weight(1f))

                // Footer
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 13.dp), // Fibonacci
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

/**
 * Single podium entry: avatar circle + name + job count, on a colored pedestal.
 */
@Composable
private fun PodiumEntry(
    entry: LeaderboardEntry,
    avatarSize: Int,
    medalColor: Color,
    medalLabel: String,
    pedestal: Int,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(89.dp), // Fibonacci column width
    ) {
        // Avatar circle
        Box(
            modifier = Modifier
                .size(avatarSize.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.2f))
                .border(2.dp, medalColor, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            val initials = entry.name.split(" ")
                .take(2)
                .joinToString("") { it.first().uppercase() }
            Text(
                text = initials,
                fontSize = (avatarSize / 3).sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }

        Spacer(modifier = Modifier.height(5.dp)) // Fibonacci xs

        // Name (truncated)
        Text(
            text = entry.name.split(" ").first(),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )

        // Jobs count
        Text(
            text = "${entry.jobsCompleted} jobs",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.7f),
        )

        Spacer(modifier = Modifier.height(5.dp)) // Fibonacci xs

        // Pedestal block
        Box(
            modifier = Modifier
                .width(55.dp) // Fibonacci
                .height(pedestal.dp)
                .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                .background(medalColor.copy(alpha = 0.3f))
                .border(
                    1.dp,
                    medalColor.copy(alpha = 0.5f),
                    RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
                ),
            contentAlignment = Alignment.TopCenter,
        ) {
            Text(
                text = "#$medalLabel",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = medalColor,
                modifier = Modifier.padding(top = 8.dp), // Fibonacci sm
            )
        }
    }
}

/**
 * Highlighted row showing the current user's position on the leaderboard.
 */
@Composable
private fun CurrentUserRow(entry: LeaderboardEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp)) // Fibonacci
            .background(Color.White.copy(alpha = 0.15f))
            .padding(13.dp), // Fibonacci
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp), // Fibonacci
    ) {
        // Rank badge
        Box(
            modifier = Modifier
                .size(34.dp) // Fibonacci
                .clip(CircleShape)
                .background(GccGreenLight.copy(alpha = 0.3f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "#${entry.rank}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }

        // Name + stats
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Text(
                text = "${entry.jobsCompleted} jobs completed",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
            )
        }

        // Badge count
        Text(
            text = "\uD83C\uDFC6 ${entry.badgeCount}",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.9f),
        )
    }
}
