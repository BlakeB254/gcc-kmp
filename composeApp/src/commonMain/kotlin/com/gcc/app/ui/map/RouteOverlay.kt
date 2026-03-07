package com.gcc.app.ui.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.theme.GccGreen
import com.gcc.app.ui.theme.TextMuted
import com.gcc.app.util.padTwo
import com.gcc.shared.model.Route

/**
 * Overlay that renders route chips in a horizontal scrollable row.
 *
 * Each chip shows a route thumbnail, the route name, checkpoint count, and payout.
 */
@Composable
fun RouteOverlay(
    routes: List<Route>,
    onRouteClick: (Route) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 4.dp),
    ) {
        items(routes, key = { it.id }) { route ->
            RouteChip(
                route = route,
                onClick = { onRouteClick(route) },
            )
        }
    }
}

@Composable
private fun RouteChip(
    route: Route,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .width(160.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        shadowElevation = 1.dp,
        color = Color.White,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Route visual thumbnail
            RouteThumbnail(
                route = route,
                modifier = Modifier.size(36.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    route.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "${route.checkpoints.size} stops",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                    route.payoutAmount?.let { payout ->
                        Text(
                            "$${payout / 100}.${(payout % 100).padTwo()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = GccGreen,
                        )
                    }
                }
            }
        }
    }
}
