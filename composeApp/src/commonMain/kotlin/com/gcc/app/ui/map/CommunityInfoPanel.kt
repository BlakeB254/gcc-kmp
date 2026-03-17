package com.gcc.app.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gcc.app.ui.theme.*
/**
 * Community info overlay shown on the full-screen map.
 *
 * Displays community area identity and key metrics
 * (total routes, registered routes, jobs completed). Designed to sit
 * in the top-start corner over the map.
 */
@Composable
fun CommunityInfoPanel(
    communityAreaName: String?,
    totalRoutes: Int,
    registeredRoutes: Int,
    jobsCompleted: Int,
    totalEarnings: Int,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.padding(8.dp),
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 4.dp,
        color = GccSurface,
    ) {
        Column(
            modifier = Modifier.padding(13.dp).widthIn(max = 200.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Community area badge
            val areaDisplay = communityAreaName ?: "North Lawndale"
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Green dot indicator
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(GccGreen),
                )
                Text(
                    areaDisplay,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                    color = GccGreenDark,
                )
            }

            // Divider
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(GccGreenLight.copy(alpha = 0.4f)),
            )

            // Stats row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                MetricItem(
                    value = "$totalRoutes",
                    label = "Routes",
                    accentColor = GccGreen,
                )
                MetricItem(
                    value = "$registeredRoutes/$totalRoutes",
                    label = "Available",
                    accentColor = GccBlue,
                )
                MetricItem(
                    value = "$jobsCompleted",
                    label = "Jobs",
                    accentColor = GccGold,
                )
            }

            // Earnings row (only if > 0)
            if (totalEarnings > 0) {
                val dollars = totalEarnings / 100
                val cents = totalEarnings % 100
                val centsStr = if (cents < 10) "0$cents" else "$cents"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "Earned",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                    )
                    Text(
                        "$$dollars.$centsStr",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = GccGreen,
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricItem(
    value: String,
    label: String,
    accentColor: androidx.compose.ui.graphics.Color,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            ),
            color = accentColor,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
            fontSize = 10.sp,
        )
    }
}
