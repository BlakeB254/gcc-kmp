package com.gcc.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gcc.app.ui.components.*
import com.gcc.app.ui.components.GccLogo
import com.gcc.app.ui.theme.*
import com.gcc.app.viewmodel.BountyViewModel
import com.gcc.app.util.padTwo
import com.gcc.shared.model.Bounty
import com.gcc.shared.model.BountyStatus
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BountyListScreen(
    onBack: () -> Unit = {},
    onBountyClick: (bountyId: Int) -> Unit = {},
) {
    val viewModel = koinInject<BountyViewModel>()
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadBounties()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Cleanup Opportunities") },
                actions = { GccLogo(size = 72.dp, modifier = Modifier.padding(end = 12.dp)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            if (uiState.isLoading) {
                SkeletonList(count = 3)
            } else if (uiState.bounties.isEmpty() && uiState.error == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "\uD83C\uDFC6",
                            style = MaterialTheme.typography.displayMedium,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "No cleanup opportunities",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextMuted,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "New reports appear here when areas need\nextra attention. Check back soon!",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSubtle,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    // Error message
                    uiState.error?.let { error ->
                        item {
                            GccCard(accentColor = CardAccentRed) {
                                Text(error, color = GccError)
                            }
                        }
                    }

                    items(uiState.bounties, key = { it.id }) { bounty ->
                        BountyListItem(
                            bounty = bounty,
                            onClick = { onBountyClick(bounty.id) },
                        )
                    }

                    item { Spacer(modifier = Modifier.height(8.dp)) }
                }
            }
        }
    }
}

@Composable
private fun BountyListItem(
    bounty: Bounty,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dollars = bounty.amount / 100
    val cents = bounty.amount % 100
    val isCleanupOpportunity = bounty.amount <= 0 && bounty.triggerReportId != null
    val amountLabel = if (isCleanupOpportunity) "Volunteer" else "$$dollars.${cents.padTwo()}"

    GccCard(
        modifier = modifier,
        accentColor = CardAccentSky,
        onClick = onClick,
    ) {
        // Title + amount row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    bounty.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BountyStatusChip(bounty.status)
                    bounty.communityArea?.let { area ->
                        Text(area, style = MaterialTheme.typography.bodySmall, color = TextMuted)
                    }
                }
            }
            Text(
                amountLabel,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = if (isCleanupOpportunity) GccSky else GccGreen,
            )
        }

        bounty.description?.let { desc ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Location map + spot photos row
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().height(100.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Mini location map with cross streets
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp)),
            ) {
                com.gcc.app.ui.map.GccMap(
                    modifier = Modifier.fillMaxSize(),
                    centerLat = bounty.locationLat,
                    centerLng = bounty.locationLng,
                    zoom = 15.5,
                    currentLocation = com.gcc.shared.model.Coordinates(
                        lat = bounty.locationLat,
                        lng = bounty.locationLng,
                    ),
                    enableGestures = false,
                    showBuildings = false,
                    showGridLines = false,
                )
                // Cross street label overlay
                bounty.neighborhood?.let { hood ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(4.dp)
                            .background(
                                Color.Black.copy(alpha = 0.6f),
                                RoundedCornerShape(4.dp),
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            hood,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            maxLines = 1,
                        )
                    }
                }
            }

            // Spot photos (right side)
            if (bounty.mediaUrls.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    bounty.mediaUrls.take(2).forEach { url ->
                        val isReal = url.startsWith("http")
                        if (isReal) {
                            com.gcc.app.ui.components.NetworkImage(
                                url = url,
                                contentDescription = "Spot photo",
                                modifier = Modifier
                                    .size(if (bounty.mediaUrls.size == 1) 100.dp else 48.dp)
                                    .clip(RoundedCornerShape(6.dp)),
                                decodeWidth = 128,
                                decodeHeight = 128,
                            )
                        } else {
                            BountyThumbnail(
                                bounty = bounty,
                                modifier = Modifier
                                    .size(if (bounty.mediaUrls.size == 1) 100.dp else 48.dp),
                            )
                        }
                    }
                    if (bounty.mediaUrls.size > 2) {
                        Text(
                            "+${bounty.mediaUrls.size - 2} more",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSubtle,
                        )
                    }
                }
            }
        }
    }
}

// Procedural thumbnail that draws a scene based on bounty content keywords
@Composable
private fun BountyThumbnail(
    bounty: Bounty,
    modifier: Modifier = Modifier,
) {
    val titleLower = bounty.title.lowercase()
    val category = when {
        "tire" in titleLower -> BountyCategory.TIRES
        "dump" in titleLower || "dumping" in titleLower -> BountyCategory.DUMPING
        "alley" in titleLower || "cleanup" in titleLower -> BountyCategory.ALLEY
        "mattress" in titleLower || "furniture" in titleLower -> BountyCategory.MATTRESS
        "trash" in titleLower || "pile" in titleLower -> BountyCategory.TRASH
        else -> BountyCategory.GENERAL
    }

    val (gradient, accentColor) = when (bounty.status) {
        BountyStatus.OPEN -> Pair(
            Brush.linearGradient(listOf(Color(0xFF2E7D32), Color(0xFF66BB6A))),
            Color(0xFF81C784),
        )
        BountyStatus.CLAIMED, BountyStatus.FIRST_REFUSAL -> Pair(
            Brush.linearGradient(listOf(Color(0xFF1565C0), Color(0xFF42A5F5))),
            Color(0xFF90CAF9),
        )
        BountyStatus.IN_PROGRESS -> Pair(
            Brush.linearGradient(listOf(Color(0xFFE65100), Color(0xFFFFA726))),
            Color(0xFFFFCC80),
        )
        BountyStatus.COMPLETED -> Pair(
            Brush.linearGradient(listOf(Color(0xFF6A1B9A), Color(0xFFAB47BC))),
            Color(0xFFCE93D8),
        )
        BountyStatus.EXPIRED, BountyStatus.CANCELLED -> Pair(
            Brush.linearGradient(listOf(Color(0xFF616161), Color(0xFF9E9E9E))),
            Color(0xFFBDBDBD),
        )
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp)),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Background gradient
            drawRect(brush = gradient)

            // Draw scene based on category
            when (category) {
                BountyCategory.TIRES -> drawTireScene(accentColor)
                BountyCategory.DUMPING -> drawDumpingScene(accentColor)
                BountyCategory.ALLEY -> drawAlleyScene(accentColor)
                BountyCategory.MATTRESS -> drawMattressScene(accentColor)
                BountyCategory.TRASH -> drawTrashScene(accentColor)
                BountyCategory.GENERAL -> drawGeneralScene(accentColor)
            }

            // Photo count overlay badge (top-right corner)
            if (bounty.mediaUrls.size > 1) {
                val badgeRadius = size.minDimension * 0.12f
                drawCircle(
                    color = Color.Black.copy(alpha = 0.6f),
                    radius = badgeRadius,
                    center = Offset(size.width - badgeRadius - 4f, badgeRadius + 4f),
                )
            }
        }
    }
}

private fun DrawScope.drawTireScene(accent: Color) {
    val w = size.width
    val h = size.height
    // Ground line
    drawRect(Color.Black.copy(alpha = 0.15f), Offset(0f, h * 0.7f), Size(w, h * 0.3f))
    // Draw 3 stacked tire circles
    val tireColor = Color(0xFF212121)
    val rimColor = accent.copy(alpha = 0.5f)
    for (i in 0..2) {
        val cx = w * (0.3f + i * 0.2f)
        val cy = h * (0.55f - i * 0.12f)
        val radius = w * 0.14f
        drawCircle(tireColor, radius, Offset(cx, cy))
        drawCircle(rimColor, radius * 0.45f, Offset(cx, cy))
        drawCircle(tireColor, radius * 0.2f, Offset(cx, cy))
    }
}

private fun DrawScope.drawDumpingScene(accent: Color) {
    val w = size.width
    val h = size.height
    // Ground
    drawRect(Color.Black.copy(alpha = 0.12f), Offset(0f, h * 0.65f), Size(w, h * 0.35f))
    // Pile of irregular shapes (debris)
    val debrisColor = Color(0xFF795548).copy(alpha = 0.7f)
    val path = Path().apply {
        moveTo(w * 0.1f, h * 0.7f)
        lineTo(w * 0.25f, h * 0.35f)
        lineTo(w * 0.4f, h * 0.45f)
        lineTo(w * 0.55f, h * 0.3f)
        lineTo(w * 0.7f, h * 0.4f)
        lineTo(w * 0.85f, h * 0.5f)
        lineTo(w * 0.9f, h * 0.7f)
        close()
    }
    drawPath(path, debrisColor)
    // Accent highlight on top
    drawCircle(accent.copy(alpha = 0.3f), w * 0.08f, Offset(w * 0.45f, h * 0.38f))
    drawCircle(accent.copy(alpha = 0.2f), w * 0.06f, Offset(w * 0.65f, h * 0.42f))
    // Warning stripes
    drawRoundRect(
        Color(0xFFFFEB3B).copy(alpha = 0.6f),
        Offset(w * 0.05f, h * 0.05f),
        Size(w * 0.25f, h * 0.1f),
        CornerRadius(4f),
    )
}

private fun DrawScope.drawAlleyScene(accent: Color) {
    val w = size.width
    val h = size.height
    // Alley perspective — two walls converging
    val wallColor = Color(0xFF8D6E63).copy(alpha = 0.5f)
    val groundColor = Color(0xFF757575).copy(alpha = 0.3f)
    // Ground plane
    drawRect(groundColor, Offset(0f, h * 0.5f), Size(w, h * 0.5f))
    // Left wall
    val leftWall = Path().apply {
        moveTo(0f, h * 0.1f)
        lineTo(w * 0.35f, h * 0.35f)
        lineTo(w * 0.35f, h)
        lineTo(0f, h)
        close()
    }
    drawPath(leftWall, wallColor)
    // Right wall
    val rightWall = Path().apply {
        moveTo(w, h * 0.1f)
        lineTo(w * 0.65f, h * 0.35f)
        lineTo(w * 0.65f, h)
        lineTo(w, h)
        close()
    }
    drawPath(rightWall, wallColor)
    // Trash bags in alley
    drawCircle(Color(0xFF37474F), w * 0.09f, Offset(w * 0.42f, h * 0.65f))
    drawCircle(Color(0xFF37474F), w * 0.08f, Offset(w * 0.55f, h * 0.7f))
    drawCircle(accent.copy(alpha = 0.3f), w * 0.06f, Offset(w * 0.48f, h * 0.58f))
}

private fun DrawScope.drawMattressScene(accent: Color) {
    val w = size.width
    val h = size.height
    // Ground
    drawRect(Color.Black.copy(alpha = 0.1f), Offset(0f, h * 0.7f), Size(w, h * 0.3f))
    // Mattress — tilted rectangle
    val mattressColor = Color(0xFFE0E0E0).copy(alpha = 0.8f)
    val mattress = Path().apply {
        moveTo(w * 0.15f, h * 0.68f)
        lineTo(w * 0.2f, h * 0.25f)
        lineTo(w * 0.75f, h * 0.2f)
        lineTo(w * 0.7f, h * 0.63f)
        close()
    }
    drawPath(mattress, mattressColor)
    // Mattress stripes
    for (i in 1..3) {
        val frac = i * 0.25f
        drawLine(
            accent.copy(alpha = 0.3f),
            Offset(w * (0.15f + frac * 0.55f), h * (0.68f - frac * 0.43f)),
            Offset(w * (0.2f + frac * 0.55f), h * (0.25f - frac * 0.05f + frac * 0.38f)),
            strokeWidth = 2f,
        )
    }
    // Second mattress behind
    drawRoundRect(
        Color(0xFFBDBDBD).copy(alpha = 0.5f),
        Offset(w * 0.5f, h * 0.35f),
        Size(w * 0.4f, h * 0.35f),
        CornerRadius(4f),
    )
}

private fun DrawScope.drawTrashScene(accent: Color) {
    val w = size.width
    val h = size.height
    // Ground
    drawRect(Color.Black.copy(alpha = 0.1f), Offset(0f, h * 0.65f), Size(w, h * 0.35f))
    // Trash bags — several overlapping circles
    val bagColor = Color(0xFF37474F)
    drawCircle(bagColor, w * 0.15f, Offset(w * 0.3f, h * 0.55f))
    drawCircle(bagColor.copy(alpha = 0.8f), w * 0.13f, Offset(w * 0.5f, h * 0.5f))
    drawCircle(bagColor.copy(alpha = 0.9f), w * 0.12f, Offset(w * 0.65f, h * 0.58f))
    drawCircle(bagColor.copy(alpha = 0.7f), w * 0.1f, Offset(w * 0.45f, h * 0.65f))
    // Tie knots on bags
    drawCircle(accent.copy(alpha = 0.5f), w * 0.03f, Offset(w * 0.3f, h * 0.42f))
    drawCircle(accent.copy(alpha = 0.5f), w * 0.03f, Offset(w * 0.5f, h * 0.39f))
    drawCircle(accent.copy(alpha = 0.5f), w * 0.03f, Offset(w * 0.65f, h * 0.48f))
    // Scattered debris
    drawCircle(Color(0xFF8D6E63).copy(alpha = 0.4f), w * 0.04f, Offset(w * 0.15f, h * 0.7f))
    drawCircle(Color(0xFF8D6E63).copy(alpha = 0.3f), w * 0.035f, Offset(w * 0.8f, h * 0.68f))
}

private fun DrawScope.drawGeneralScene(accent: Color) {
    val w = size.width
    val h = size.height
    // Generic report thumbnail — camera/pin icon style
    drawRect(Color.Black.copy(alpha = 0.1f), Offset(0f, h * 0.6f), Size(w, h * 0.4f))
    // Pin shape
    val pinColor = accent.copy(alpha = 0.7f)
    drawCircle(pinColor, w * 0.18f, Offset(w * 0.5f, h * 0.35f))
    val pinPath = Path().apply {
        moveTo(w * 0.5f - w * 0.1f, h * 0.45f)
        lineTo(w * 0.5f, h * 0.7f)
        lineTo(w * 0.5f + w * 0.1f, h * 0.45f)
        close()
    }
    drawPath(pinPath, pinColor)
    drawCircle(Color.White.copy(alpha = 0.5f), w * 0.08f, Offset(w * 0.5f, h * 0.33f))
}

private enum class BountyCategory {
    TIRES, DUMPING, ALLEY, MATTRESS, TRASH, GENERAL
}

@Composable
private fun BountyStatusChip(status: BountyStatus, modifier: Modifier = Modifier) {
    val (text, dotColor, bgColor) = when (status) {
        BountyStatus.OPEN -> Triple("Open", GccAvailable, ChipBgAvailable)
        BountyStatus.FIRST_REFUSAL -> Triple("First Refusal", GccClaimed, ChipBgClaimed)
        BountyStatus.CLAIMED -> Triple("Claimed", GccClaimed, ChipBgClaimed)
        BountyStatus.IN_PROGRESS -> Triple("In Progress", GccInProgress, ChipBgInProgress)
        BountyStatus.COMPLETED -> Triple("Completed", GccCompleted, ChipBgCompleted)
        BountyStatus.EXPIRED -> Triple("Expired", TextMuted, SurfaceMuted)
        BountyStatus.CANCELLED -> Triple("Cancelled", GccError, ChipBgDisputed)
    }
    StatusChip(text = text, dotColor = dotColor, backgroundColor = bgColor, modifier = modifier)
}
