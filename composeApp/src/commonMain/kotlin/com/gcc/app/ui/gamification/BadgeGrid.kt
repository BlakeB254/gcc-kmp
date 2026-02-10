package com.gcc.app.ui.gamification

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcc.shared.model.Badge

/**
 * Grid layout displaying all badges grouped by category.
 *
 * Unlocked badges appear first with full color, followed by
 * locked badges with progress indicators.
 */
@Composable
fun BadgeGrid(
    badges: List<Badge>,
    onBadgeClick: ((Badge) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val sortedBadges = badges.sortedWith(
        compareByDescending<Badge> { it.isUnlocked }
            .thenBy { it.category.ordinal }
            .thenBy { it.tier.ordinal }
    )

    val unlockedCount = badges.count { it.isUnlocked }

    Column(modifier = modifier) {
        Text(
            text = "\uD83C\uDFC6 $unlockedCount / ${badges.size} Badges Earned",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        )
        Spacer(modifier = Modifier.height(16.dp))

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 100.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(sortedBadges, key = { it.id }) { badge ->
                BadgeCard(
                    badge = badge,
                    onClick = onBadgeClick?.let { { it(badge) } },
                )
            }
        }
    }
}
