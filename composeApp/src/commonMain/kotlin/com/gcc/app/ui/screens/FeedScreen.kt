package com.gcc.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.components.AvatarStack
import com.gcc.app.ui.components.GccCard
import com.gcc.app.ui.components.GccLogo
import com.gcc.app.ui.components.SkeletonList
import com.gcc.app.ui.map.GccMap
import com.gcc.app.ui.map.MapStyles
import com.gcc.app.ui.theme.*
import com.gcc.app.util.padTwo
import com.gcc.app.viewmodel.FeedViewModel
import com.gcc.shared.model.Coordinates
import com.gcc.shared.model.EarlyAdopterTier
import com.gcc.shared.model.FeedItem
import com.gcc.shared.model.FeedItemType
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class, kotlin.time.ExperimentalTime::class)
@Composable
fun FeedScreen(
    onBack: () -> Unit = {},
) {
    val viewModel = koinInject<FeedViewModel>()
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        viewModel.loadFeed()
    }

    // Infinite scroll: detect when last item is visible
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            lastVisibleIndex >= totalItems - 3 && !uiState.isLoadingMore && uiState.hasMore && !uiState.isLoading
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            viewModel.loadMore()
        }
    }

    // Comment bottom sheet
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    if (uiState.selectedItemId != null) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.closeComments() },
            sheetState = sheetState,
        ) {
            CommentSheet(
                comments = uiState.comments,
                isLoading = uiState.isLoadingComments,
                isPosting = uiState.isPostingComment,
                onPost = { viewModel.postComment(it) },
                onDismiss = { viewModel.closeComments() },
            )
        }
    }

    // Caption input dialog
    if (uiState.captionItemId != null) {
        CaptionDialog(
            isSaving = uiState.isSavingCaption,
            onSave = { viewModel.saveCaption(it) },
            onDismiss = { viewModel.cancelCaption() },
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Community Feed") },
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
                SkeletonList(count = 5)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(13.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    // Active contributors summary
                    if (uiState.items.size >= 3) {
                        item {
                            val recentNames = remember(uiState.items) {
                                uiState.items.map { it.actorName }.distinct().take(5)
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    "Active Contributors",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextMuted,
                                )
                                AvatarStack(
                                    names = recentNames,
                                    maxVisible = 5,
                                    size = 28,
                                )
                            }
                        }
                    }

                    // Filter chips row
                    item {
                        FilterChipsRow(
                            selectedFilter = uiState.selectedFilter,
                            onFilterSelected = { viewModel.setFilter(it) },
                        )
                    }

                    // Error message
                    uiState.error?.let { error ->
                        item {
                            GccCard(
                                accentColor = CardAccentRed,
                                modifier = Modifier.padding(horizontal = 13.dp),
                            ) {
                                Text(error, color = GccError)
                            }
                        }
                    }

                    if (uiState.items.isEmpty() && uiState.error == null) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(32.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        "\uD83D\uDCF0",
                                        style = MaterialTheme.typography.displayMedium,
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        "No activity yet",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = TextMuted,
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        "Spot reports, cleanups, and community\nupdates will appear here.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = TextSubtle,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    )
                                }
                            }
                        }
                    }

                    items(uiState.items, key = { it.id }) { item ->
                        FeedCard(
                            item = item,
                            onCommentTap = { viewModel.openComments(item.id) },
                            onCaptionTap = { viewModel.startAddCaption(item.id) },
                            onOptInProfile = { viewModel.optInProfile(item.id) },
                            modifier = Modifier.padding(horizontal = 13.dp),
                        )
                    }

                    // Load more indicator
                    if (uiState.isLoadingMore) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(13.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    color = GccGreen,
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                        }
                    }

                    item { Spacer(modifier = Modifier.height(8.dp)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterChipsRow(
    selectedFilter: FeedItemType?,
    onFilterSelected: (FeedItemType?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selectedFilter == null,
            onClick = { onFilterSelected(null) },
            label = { Text("All", style = MaterialTheme.typography.labelMedium) },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = GccGreen,
                selectedLabelColor = Color.White,
                containerColor = SurfaceMuted,
                labelColor = TextMuted,
            ),
            shape = RoundedCornerShape(8.dp),
        )

        data class FilterOption(val type: FeedItemType, val label: String)
        val filters = listOf(
            FilterOption(FeedItemType.SPOT_REPORT, "Spots"),
            FilterOption(FeedItemType.BOUNTY_CREATED, "Bounties"),
            FilterOption(FeedItemType.JOB_COMPLETED, "Jobs"),
            FilterOption(FeedItemType.VOLUNTEER_CLEANUP, "Cleanups"),
            FilterOption(FeedItemType.LEADERBOARD_CHANGE, "Rankings"),
            FilterOption(FeedItemType.ARTICLE, "Articles"),
        )

        filters.forEach { filter ->
            FilterChip(
                selected = selectedFilter == filter.type,
                onClick = { onFilterSelected(filter.type) },
                label = { Text(filter.label, style = MaterialTheme.typography.labelMedium) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = GccGreen,
                    selectedLabelColor = Color.White,
                    containerColor = SurfaceMuted,
                    labelColor = TextMuted,
                ),
                shape = RoundedCornerShape(8.dp),
            )
        }
    }
}

@Composable
private fun FeedCard(
    item: FeedItem,
    onCommentTap: () -> Unit = {},
    onCaptionTap: () -> Unit = {},
    onOptInProfile: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Article cards get a distinct visual treatment
    if (item.type == FeedItemType.ARTICLE) {
        ArticleCard(item = item, onCommentTap = onCommentTap, modifier = modifier)
        return
    }
    val accentColor = when (item.type) {
        FeedItemType.SPOT_REPORT -> CardAccentPurple
        FeedItemType.BOUNTY_CREATED, FeedItemType.BOUNTY_COMPLETED -> CardAccentSky
        FeedItemType.JOB_COMPLETED -> CardAccentGreen
        FeedItemType.VOLUNTEER_CLEANUP -> CardAccentBlue
        FeedItemType.BADGE_UNLOCKED -> CardAccentRed
        FeedItemType.LEADERBOARD_CHANGE -> CardAccentSky
        FeedItemType.ARTICLE -> GccGreenDark
    }

    GccCard(
        modifier = modifier,
        accentColor = accentColor,
    ) {
        // Actor row: initials circle + name + tier + time
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Initials circle (34dp)
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    item.actorName.take(2).uppercase(),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                    color = accentColor,
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.actorName,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (item.actorTier != EarlyAdopterTier.STANDARD) {
                        Spacer(modifier = Modifier.width(5.dp))
                        FeedTierBadge(item.actorTier)
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            @OptIn(kotlin.time.ExperimentalTime::class)
            Text(
                timeAgo(item.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = TextSubtle,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Title
        Text(
            item.title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        )

        // Description
        if (item.description.isNotBlank()) {
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                item.description,
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Media thumbnails (before/after or report photos)
        val allMedia = item.mediaUrls + item.completionMediaUrls
        if (allMedia.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            FeedMediaRow(
                mediaUrls = item.mediaUrls,
                completionMediaUrls = item.completionMediaUrls,
                accentColor = accentColor,
                feedType = item.type,
            )
        }

        // Mini map showing location
        if (item.locationLat != null && item.locationLng != null) {
            Spacer(modifier = Modifier.height(8.dp))
            FeedMiniMap(
                lat = item.locationLat!!,
                lng = item.locationLng!!,
                accentColor = accentColor,
            )
        }

        // Amount / Points badge row
        val hasAmountOrPoints = item.amount != null || item.points != null
        if (hasAmountOrPoints) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item.amount?.let { amount ->
                    val dollars = amount / 100
                    val cents = amount % 100
                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = GccGreen.copy(alpha = 0.1f),
                    ) {
                        Text(
                            "$$dollars.${cents.padTwo()}",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                            ),
                            color = GccGreen,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
                item.points?.let { points ->
                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = CardAccentPurple.copy(alpha = 0.1f),
                    ) {
                        Text(
                            "+$points pts",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                            ),
                            color = CardAccentPurple,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }

        // User caption (social feature — user's own commentary on their event)
        item.caption?.let { caption ->
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = SurfaceMuted,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        "\u201C",
                        style = MaterialTheme.typography.titleLarge,
                        color = accentColor.copy(alpha = 0.5f),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        caption,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // Community area
        item.communityArea?.let { area ->
            Spacer(modifier = Modifier.height(5.dp))
            Text(
                area,
                style = MaterialTheme.typography.labelSmall,
                color = TextSubtle,
            )
        }

        // Comment count + interaction row
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Comment button
            Surface(
                onClick = onCommentTap,
                shape = RoundedCornerShape(6.dp),
                color = Color.Transparent,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "\uD83D\uDCAC",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        if (item.commentCount > 0) "${item.commentCount}" else "Comment",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                    )
                }
            }

            // Caption prompt for user's own events
            if (item.caption == null && item.actorId != null) {
                Surface(
                    onClick = onCaptionTap,
                    shape = RoundedCornerShape(6.dp),
                    color = Color.Transparent,
                ) {
                    Text(
                        "\u270F\uFE0F Add a caption...",
                        style = MaterialTheme.typography.labelSmall,
                        color = GccGreen,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            // Profile opt-in prompt (1-click)
            if (!item.actorProfileVisible && item.actorId != null) {
                Surface(
                    onClick = onOptInProfile,
                    shape = RoundedCornerShape(6.dp),
                    color = GccGreen.copy(alpha = 0.08f),
                ) {
                    Text(
                        "\uD83D\uDC64 Add profile",
                        style = MaterialTheme.typography.labelSmall,
                        color = GccGreen,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * Horizontally scrollable row of photo thumbnails.
 * Renders Canvas-based placeholders since demo mode has no real image URLs.
 * Shows "Before" / "After" labels when both media and completion media exist.
 */
@Composable
private fun FeedMediaRow(
    mediaUrls: List<String>,
    completionMediaUrls: List<String>,
    accentColor: Color,
    feedType: FeedItemType,
) {
    val hasBefore = mediaUrls.isNotEmpty()
    val hasAfter = completionMediaUrls.isNotEmpty()
    val showLabels = hasBefore && hasAfter

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // "Before" photos
        mediaUrls.forEachIndexed { index, url ->
            PhotoThumbnail(
                url = url,
                label = if (showLabels) "Before" else null,
                sublabel = if (mediaUrls.size > 1) "${index + 1}" else null,
                accentColor = accentColor,
                feedType = feedType,
                isBefore = true,
            )
        }

        // "After" / completion photos
        completionMediaUrls.forEachIndexed { index, url ->
            PhotoThumbnail(
                url = url,
                label = if (showLabels) "After" else if (hasAfter && !hasBefore) "Verified" else null,
                sublabel = if (completionMediaUrls.size > 1) "${index + 1}" else null,
                accentColor = if (hasAfter && !hasBefore) GccGreen else GccCompleted,
                feedType = feedType,
                isBefore = false,
            )
        }
    }
}

/**
 * Canvas-drawn photo thumbnail placeholder.
 * Shows a gradient background, a camera/scene icon, and optional label.
 */
@Composable
private fun PhotoThumbnail(
    url: String = "",
    label: String?,
    sublabel: String?,
    accentColor: Color,
    feedType: FeedItemType,
    isBefore: Boolean,
) {
    val isRealUrl = url.startsWith("http://") || url.startsWith("https://")

    Box(
        modifier = Modifier
            .size(width = 120.dp, height = 89.dp)
            .clip(RoundedCornerShape(8.dp)),
    ) {
        if (isRealUrl) {
            // Real photo from server
            com.gcc.app.ui.components.NetworkImage(
                url = url,
                contentDescription = label ?: "Photo",
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Canvas: full scene when no image, subtle overlay when image exists
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            if (isRealUrl) {
                // Just a subtle bottom gradient for label readability
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.3f)),
                        startY = h * 0.5f,
                        endY = h,
                    ),
                )
            } else {
            // Gradient background (placeholder mode)
            val gradientColors = if (isBefore) {
                listOf(
                    accentColor.copy(alpha = 0.25f),
                    accentColor.copy(alpha = 0.08f),
                )
            } else {
                listOf(
                    GccGreen.copy(alpha = 0.25f),
                    GccGreen.copy(alpha = 0.08f),
                )
            }
            drawRoundRect(
                brush = Brush.linearGradient(
                    colors = gradientColors,
                    start = Offset(0f, 0f),
                    end = Offset(w, h),
                ),
                cornerRadius = CornerRadius(8f, 8f),
                size = size,
            )

            // Draw a scene representation based on feed type
            when (feedType) {
                FeedItemType.SPOT_REPORT -> drawSpotScene(w, h, accentColor)
                FeedItemType.BOUNTY_COMPLETED, FeedItemType.BOUNTY_CREATED -> drawBountyScene(w, h, isBefore)
                FeedItemType.JOB_COMPLETED -> drawRouteScene(w, h)
                FeedItemType.VOLUNTEER_CLEANUP -> drawCleanupScene(w, h)
                FeedItemType.BADGE_UNLOCKED -> { /* no photos for badges */ }
                FeedItemType.LEADERBOARD_CHANGE -> drawRouteScene(w, h)
                FeedItemType.ARTICLE -> { /* articles use real images */ }
            }
            } // end else (placeholder mode)

            // Camera icon indicator (top-right corner)
            drawRoundRect(
                color = Color.Black.copy(alpha = 0.3f),
                topLeft = Offset(w - 28f, 4f),
                size = Size(24f, 20f),
                cornerRadius = CornerRadius(4f, 4f),
            )
            // Lens circle
            drawCircle(
                color = Color.White.copy(alpha = 0.7f),
                radius = 5f,
                center = Offset(w - 16f, 14f),
            )
        }

        // Label overlay at bottom
        if (label != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp),
                shape = RoundedCornerShape(4.dp),
                color = Color.Black.copy(alpha = 0.6f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                    )
                    sublabel?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }
    }
}

/** Draw a spot report scene: trash pile silhouette */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSpotScene(
    w: Float, h: Float, accentColor: Color,
) {
    // Ground line
    drawLine(
        color = accentColor.copy(alpha = 0.3f),
        start = Offset(0f, h * 0.75f),
        end = Offset(w, h * 0.75f),
        strokeWidth = 1.5f,
    )
    // Trash pile shapes (irregular rectangles)
    val pileColor = accentColor.copy(alpha = 0.2f)
    drawRoundRect(pileColor, Offset(w * 0.15f, h * 0.45f), Size(w * 0.25f, h * 0.3f), CornerRadius(3f))
    drawRoundRect(pileColor, Offset(w * 0.35f, h * 0.35f), Size(w * 0.2f, h * 0.4f), CornerRadius(3f))
    drawRoundRect(pileColor, Offset(w * 0.5f, h * 0.5f), Size(w * 0.22f, h * 0.25f), CornerRadius(3f))
    // Warning triangle
    val triPath = Path().apply {
        moveTo(w * 0.78f, h * 0.3f)
        lineTo(w * 0.72f, h * 0.55f)
        lineTo(w * 0.84f, h * 0.55f)
        close()
    }
    drawPath(triPath, color = accentColor.copy(alpha = 0.4f))
    drawPath(triPath, color = accentColor.copy(alpha = 0.6f), style = Stroke(width = 1.5f))
}

/** Draw a bounty scene: before (dirty) or after (clean) */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBountyScene(
    w: Float, h: Float, isBefore: Boolean,
) {
    if (isBefore) {
        // Messy scene
        val messColor = CardAccentSky.copy(alpha = 0.2f)
        drawRoundRect(messColor, Offset(w * 0.1f, h * 0.5f), Size(w * 0.3f, h * 0.25f), CornerRadius(2f))
        drawRoundRect(messColor, Offset(w * 0.45f, h * 0.4f), Size(w * 0.2f, h * 0.35f), CornerRadius(2f))
        drawRoundRect(messColor, Offset(w * 0.6f, h * 0.55f), Size(w * 0.25f, h * 0.2f), CornerRadius(2f))
        drawLine(CardAccentSky.copy(alpha = 0.3f), Offset(0f, h * 0.75f), Offset(w, h * 0.75f), 1.5f)
    } else {
        // Clean scene with checkmark
        drawLine(GccGreen.copy(alpha = 0.3f), Offset(0f, h * 0.75f), Offset(w, h * 0.75f), 1.5f)
        // Sparkle dots
        val sparkle = GccGreen.copy(alpha = 0.3f)
        drawCircle(sparkle, 3f, Offset(w * 0.25f, h * 0.4f))
        drawCircle(sparkle, 2f, Offset(w * 0.6f, h * 0.35f))
        drawCircle(sparkle, 2.5f, Offset(w * 0.45f, h * 0.55f))
        drawCircle(sparkle, 1.5f, Offset(w * 0.75f, h * 0.5f))
        // Checkmark
        val checkPath = Path().apply {
            moveTo(w * 0.4f, h * 0.45f)
            lineTo(w * 0.48f, h * 0.55f)
            lineTo(w * 0.62f, h * 0.35f)
        }
        drawPath(checkPath, color = GccGreen.copy(alpha = 0.6f), style = Stroke(width = 3f))
    }
}

/** Draw a route completion scene: path line with checkpoints */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRouteScene(w: Float, h: Float) {
    val routeColor = GccGreen.copy(alpha = 0.35f)
    // Winding path
    val path = Path().apply {
        moveTo(w * 0.1f, h * 0.7f)
        quadraticTo(w * 0.3f, h * 0.3f, w * 0.5f, h * 0.5f)
        quadraticTo(w * 0.7f, h * 0.7f, w * 0.9f, h * 0.3f)
    }
    drawPath(path, color = routeColor, style = Stroke(width = 3f))
    // Checkpoint dots
    drawCircle(GccGreen.copy(alpha = 0.5f), 5f, Offset(w * 0.1f, h * 0.7f))
    drawCircle(GccGreen.copy(alpha = 0.5f), 5f, Offset(w * 0.5f, h * 0.5f))
    drawCircle(GccGreen.copy(alpha = 0.5f), 5f, Offset(w * 0.9f, h * 0.3f))
    // Completed checkmark on last dot
    val check = Path().apply {
        moveTo(w * 0.86f, h * 0.3f)
        lineTo(w * 0.89f, h * 0.35f)
        lineTo(w * 0.95f, h * 0.22f)
    }
    drawPath(check, color = GccGreen.copy(alpha = 0.7f), style = Stroke(width = 2f))
}

/** Draw a volunteer cleanup scene: broom and bag */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCleanupScene(w: Float, h: Float) {
    val cleanColor = CardAccentBlue.copy(alpha = 0.3f)
    // Ground
    drawLine(cleanColor, Offset(0f, h * 0.75f), Offset(w, h * 0.75f), 1.5f)
    // Trash bag shape
    drawRoundRect(
        cleanColor.copy(alpha = 0.5f),
        Offset(w * 0.55f, h * 0.35f),
        Size(w * 0.25f, h * 0.4f),
        CornerRadius(6f),
    )
    // Bag tie
    drawCircle(cleanColor, 4f, Offset(w * 0.675f, h * 0.35f))
    // Broom handle (diagonal line)
    drawLine(
        cleanColor.copy(alpha = 0.6f),
        start = Offset(w * 0.2f, h * 0.2f),
        end = Offset(w * 0.35f, h * 0.7f),
        strokeWidth = 2.5f,
    )
    // Broom bristles
    for (i in -2..2) {
        drawLine(
            cleanColor.copy(alpha = 0.4f),
            start = Offset(w * 0.35f, h * 0.7f),
            end = Offset(w * 0.35f + i * 3f, h * 0.78f),
            strokeWidth = 1.5f,
        )
    }
}

/**
 * Mini map view showing the feed item's location.
 * Uses the existing GccMap composable centered on the coordinates.
 */
@Composable
private fun FeedMiniMap(
    lat: Double,
    lng: Double,
    accentColor: Color,
) {
    Column {
        // Map header with location pin label
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 4.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(accentColor),
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                "Location",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = TextMuted,
            )
        }

        // Map container
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(RoundedCornerShape(8.dp)),
        ) {
            GccMap(
                modifier = Modifier.fillMaxSize(),
                centerLat = lat,
                centerLng = lng,
                zoom = MapStyles.ZOOM_ROUTE,
                currentLocation = Coordinates(lat = lat, lng = lng),
                enableGestures = false,
                showBuildings = false,
                showGridLines = false,
            )

            // Subtle gradient overlay at bottom for visual blending
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                GccSurface.copy(alpha = 0.4f),
                            ),
                        ),
                    ),
            )
        }
    }
}

@Composable
private fun FeedTierBadge(tier: EarlyAdopterTier) {
    val (color, label) = when (tier) {
        EarlyAdopterTier.FOUNDER -> BadgeGold to "Founder"
        EarlyAdopterTier.PIONEER -> BadgeSilver to "Pioneer"
        EarlyAdopterTier.EARLY -> BadgeBronze to "Early"
        EarlyAdopterTier.STANDARD -> TextMuted to "Standard"
    }
    Surface(
        shape = RoundedCornerShape(3.dp),
        color = color.copy(alpha = 0.15f),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
            ),
            color = color,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

@OptIn(kotlin.time.ExperimentalTime::class)
private fun timeAgo(timestamp: kotlinx.datetime.Instant): String {
    val now = kotlin.time.Clock.System.now()
    val nowEpoch = now.toEpochMilliseconds()
    val thenEpoch = timestamp.toEpochMilliseconds()
    val diffMs = nowEpoch - thenEpoch
    val diffSeconds = diffMs / 1000
    val diffMinutes = diffSeconds / 60
    val diffHours = diffMinutes / 60
    val diffDays = diffHours / 24

    return when {
        diffSeconds < 60 -> "just now"
        diffMinutes < 60 -> "${diffMinutes}m"
        diffHours < 24 -> "${diffHours}h"
        diffDays < 7 -> "${diffDays}d"
        diffDays < 30 -> "${diffDays / 7}w"
        else -> {
            @Suppress("DEPRECATION")
            val dt = timestamp.toLocalDateTime(TimeZone.currentSystemDefault())
            @Suppress("DEPRECATION")
            "${dt.monthNumber}/${dt.dayOfMonth}"
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
// Comment Bottom Sheet
// ─────────────────────────────────────────────────────────────────────

@Composable
private fun CommentSheet(
    comments: List<com.gcc.shared.model.FeedComment>,
    isLoading: Boolean,
    isPosting: Boolean,
    onPost: (String) -> Unit,
    onDismiss: () -> Unit = {},
) {
    var commentText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Comments",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Close")
            }
        }
        Spacer(modifier = Modifier.height(4.dp))

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxWidth().height(100.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = GccGreen, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        } else if (comments.isEmpty()) {
            Text(
                "No comments yet — be the first!",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        } else {
            comments.forEach { comment ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(GccGreen.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            comment.authorName.take(2).uppercase(),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = GccGreen,
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                comment.authorName,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            )
                            Text(
                                timeAgo(comment.timestamp),
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSubtle,
                            )
                        }
                        Text(
                            comment.text,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))

        // Post comment input
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = commentText,
                onValueChange = { commentText = it },
                placeholder = { Text("Write a comment...") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (commentText.isNotBlank()) {
                            onPost(commentText.trim())
                            commentText = ""
                        }
                    },
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = GccGreen,
                    cursorColor = GccGreen,
                ),
            )
            Button(
                onClick = {
                    if (commentText.isNotBlank()) {
                        onPost(commentText.trim())
                        commentText = ""
                    }
                },
                enabled = commentText.isNotBlank() && !isPosting,
                colors = ButtonDefaults.buttonColors(containerColor = GccGreen),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                if (isPosting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Text("Post", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
// Caption Dialog
// ─────────────────────────────────────────────────────────────────────

@Composable
private fun CaptionDialog(
    isSaving: Boolean,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var captionText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Your Caption") },
        text = {
            Column {
                Text(
                    "Share what this moment meant to you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = captionText,
                    onValueChange = { captionText = it },
                    placeholder = { Text("Your story...") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = GccGreen,
                        cursorColor = GccGreen,
                    ),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (captionText.isNotBlank()) onSave(captionText.trim()) },
                enabled = captionText.isNotBlank() && !isSaving,
                colors = ButtonDefaults.buttonColors(containerColor = GccGreen),
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Text("Save")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextMuted)
            }
        },
    )
}

// ─────────────────────────────────────────────────────────────────────
// Article Card (GCC Admin Posts)
// ─────────────────────────────────────────────────────────────────────

@Composable
private fun ArticleCard(
    item: FeedItem,
    onCommentTap: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    GccCard(
        modifier = modifier,
        accentColor = GccGreenDark,
    ) {
        // GCC branding header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GccLogo(size = 28.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "GCC",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = GccGreenDark,
                )
                Text(
                    "Community Update",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSubtle,
                )
            }
            Text(
                timeAgo(item.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = TextSubtle,
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Headline
        Text(
            item.title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        )

        // Body
        if (item.description.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                item.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Hero image placeholder
        if (item.mediaUrls.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(GccGreenDark.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "\uD83D\uDCF0",
                    style = MaterialTheme.typography.displaySmall,
                )
            }
        }

        // Comment button
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
            onClick = onCommentTap,
            shape = RoundedCornerShape(6.dp),
            color = Color.Transparent,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("\uD83D\uDCAC", style = MaterialTheme.typography.labelSmall)
                Text(
                    if (item.commentCount > 0) "${item.commentCount}" else "Comment",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
            }
        }
    }
}
