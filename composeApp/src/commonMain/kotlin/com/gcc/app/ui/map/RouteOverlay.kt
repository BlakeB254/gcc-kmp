package com.gcc.app.ui.map

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.components.NetworkImage
import com.gcc.app.ui.theme.GccBlue
import com.gcc.app.ui.theme.GccGreen
import com.gcc.app.ui.theme.TextMuted
import com.gcc.app.util.padTwo
import com.gcc.shared.model.Block
import com.gcc.shared.model.Route
import com.gcc.shared.ui.drawPhotoPointScene

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
    selectedRouteId: Int? = null,
) {
    val listState = rememberLazyListState()
    val selectedIndex = routes.indexOfFirst { it.id == selectedRouteId }
    LaunchedEffect(selectedIndex) {
        if (selectedIndex >= 0) listState.animateScrollToItem(selectedIndex)
    }

    LazyRow(
        modifier = modifier.padding(8.dp),
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 4.dp),
    ) {
        items(routes, key = { it.id }) { route ->
            RouteChip(
                route = route,
                isSelected = route.id == selectedRouteId,
                onClick = { onRouteClick(route) },
            )
        }
    }
}

/**
 * Overlay for block records that are not necessarily released for cleanup work.
 *
 * These are inventory records: visible on the map for review, but not labelled
 * as worker-claimable route opportunities.
 */
@Composable
fun BlockOverlay(
    blocks: List<Block>,
    onBlockClick: (Block) -> Unit,
    modifier: Modifier = Modifier,
    selectedBlockId: Int? = null,
) {
    val listState = rememberLazyListState()
    val selectedIndex = blocks.indexOfFirst { it.id == selectedBlockId }
    LaunchedEffect(selectedIndex) {
        if (selectedIndex >= 0) listState.animateScrollToItem(selectedIndex)
    }

    LazyRow(
        modifier = modifier.padding(8.dp),
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 4.dp),
    ) {
        itemsIndexed(blocks, key = { _, block -> block.id }) { _, block ->
            BlockChip(
                block = block,
                isSelected = block.id == selectedBlockId,
                onClick = { onBlockClick(block) },
            )
        }
    }
}

@Composable
private fun BlockChip(
    block: Block,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .width(184.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        shadowElevation = 1.dp,
        color = Color.White,
        border = if (isSelected) BorderStroke(2.dp, GccGreen) else null,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BlockThumbnail(
                block = block,
                modifier = Modifier.size(40.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    block.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                )
                Spacer(modifier = Modifier.height(3.dp))
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        block.code.ifBlank { "Block" },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                    Text(
                        "Record",
                        style = MaterialTheme.typography.labelSmall,
                        color = GccBlue,
                    )
                }
            }
        }
    }
}

@Composable
private fun BlockThumbnail(
    block: Block,
    modifier: Modifier = Modifier,
) {
    if (block.displayImage != null) {
        NetworkImage(
            url = block.displayImage!!,
            contentDescription = block.name,
            modifier = modifier.clip(RoundedCornerShape(6.dp)),
            contentScale = ContentScale.Crop,
        )
    } else {
        androidx.compose.foundation.layout.Box(
            modifier = modifier.clip(RoundedCornerShape(6.dp)),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawPhotoPointScene()
            }
        }
    }
}

@Composable
private fun RouteChip(
    route: Route,
    isSelected: Boolean,
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
        border = if (isSelected) BorderStroke(2.dp, GccGreen) else null,
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
