package com.gcc.admin.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gcc.admin.ui.components.StatusBadge
import com.gcc.admin.ui.theme.AdminError
import com.gcc.admin.ui.theme.AdminOnSurfaceVariant
import com.gcc.admin.ui.theme.AdminOutline
import com.gcc.admin.ui.theme.AdminPrimary
import com.gcc.admin.ui.theme.AdminSuccess
import com.gcc.admin.ui.theme.AdminSurface
import com.gcc.admin.ui.theme.AdminTheme
import com.gcc.admin.ui.theme.AdminWarning
import com.gcc.admin.ui.theme.StatusAvailable
import com.gcc.admin.ui.theme.StatusWarning
import com.gcc.admin.viewmodel.LiveTrackingViewModel
import com.gcc.shared.model.LiveRouteSession
import com.gcc.shared.model.RouteMarker
import com.gcc.shared.model.RouteMarkerType
import com.gcc.shared.model.RouteMessage
import com.gcc.shared.model.RouteSessionStatus
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.koinInject

// ── Main Screen ─────────────────────────────────────────────────────────────

@Composable
fun AdminLiveTrackingScreen() {
    val viewModel = koinInject<LiveTrackingViewModel>()
    val sessions by viewModel.activeSessions.collectAsState()
    val selected by viewModel.selectedSession.collectAsState()
    val messageText by viewModel.messageText.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    Row(modifier = Modifier.fillMaxSize()) {
        // ── Left Panel: Session List (40 %) ─────────────────────────────
        SessionListPanel(
            sessions = sessions,
            selectedId = selected?.id,
            isLoading = isLoading,
            error = error,
            onSessionClick = { viewModel.selectSession(it) },
            onRefresh = { viewModel.loadActiveSessions() },
            modifier = Modifier.weight(0.4f).fillMaxHeight(),
        )

        VerticalDivider(color = AdminOutline, thickness = 1.dp)

        // ── Right Panel: Session Detail (60 %) ─────────────────────────
        SessionDetailPanel(
            session = selected,
            messageText = messageText,
            onMessageTextChange = { viewModel.updateMessageText(it) },
            onSendMessage = { sessionId, content ->
                viewModel.sendMessage(sessionId, content)
            },
            modifier = Modifier.weight(0.6f).fillMaxHeight(),
        )
    }
}

// ── Left Panel: Active Sessions List ────────────────────────────────────────

@Composable
private fun SessionListPanel(
    sessions: List<LiveRouteSession>,
    selectedId: Int?,
    isLoading: Boolean,
    error: String?,
    onSessionClick: (Int) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(AdminTheme.spacing.lg)) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.sm),
            ) {
                Text("Live Tracking", style = MaterialTheme.typography.headlineMedium)
                // Count badge
                val activeCount = sessions.count { it.status == RouteSessionStatus.ACTIVE }
                if (activeCount > 0) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(AdminPrimary)
                            .size(26.dp),
                    ) {
                        Text(
                            text = "$activeCount",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.xs),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                }
                IconButton(onClick = onRefresh) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh sessions",
                        tint = AdminOnSurfaceVariant,
                    )
                }
            }
        }

        error?.let {
            Spacer(modifier = Modifier.height(AdminTheme.spacing.sm))
            Text(it, color = AdminError, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(modifier = Modifier.height(AdminTheme.spacing.md))

        // Session list
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(AdminTheme.spacing.sm),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(sessions, key = { it.id }) { session ->
                SessionListItem(
                    session = session,
                    isSelected = session.id == selectedId,
                    onClick = { onSessionClick(session.id) },
                )
            }

            if (sessions.isEmpty() && !isLoading) {
                item {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = AdminTheme.spacing.xxl),
                    ) {
                        Text(
                            text = "No active sessions",
                            style = MaterialTheme.typography.bodyLarge,
                            color = AdminOnSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionListItem(
    session: LiveRouteSession,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (isSelected) AdminPrimary else Color.Transparent
    val progress = if (session.checkpointsTotal > 0) {
        session.checkpointsCompleted.toFloat() / session.checkpointsTotal
    } else {
        0f
    }

    Surface(
        tonalElevation = AdminTheme.elevation.card,
        shape = RoundedCornerShape(AdminTheme.spacing.sm),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(AdminTheme.spacing.sm),
            )
            .clip(RoundedCornerShape(AdminTheme.spacing.sm))
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(AdminTheme.spacing.md)) {
            // Worker name + route code
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = session.workerName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = session.routeCode,
                        style = MaterialTheme.typography.bodySmall,
                        color = AdminOnSurfaceVariant,
                    )
                }
                SessionStatusBadge(session.status)
            }

            Spacer(modifier = Modifier.height(AdminTheme.spacing.sm))

            // Progress bar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.sm),
                modifier = Modifier.fillMaxWidth(),
            ) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.weight(1f).height(6.dp),
                    color = AdminPrimary,
                    trackColor = AdminOutline.copy(alpha = 0.3f),
                )
                Text(
                    text = "${session.checkpointsCompleted}/${session.checkpointsTotal}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AdminOnSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(AdminTheme.spacing.xs))

            // Last update
            val localTime = session.lastUpdateAt
                .toLocalDateTime(TimeZone.currentSystemDefault())
            Text(
                text = "Updated ${formatTime(localTime)}",
                style = MaterialTheme.typography.labelSmall,
                color = AdminOnSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SessionStatusBadge(status: RouteSessionStatus) {
    val (label, color) = when (status) {
        RouteSessionStatus.ACTIVE -> "Active" to StatusAvailable
        RouteSessionStatus.PAUSED -> "Paused" to StatusWarning
        RouteSessionStatus.COMPLETED -> "Completed" to AdminSuccess
        RouteSessionStatus.ABANDONED -> "Abandoned" to AdminError
    }
    StatusBadge(text = label, color = color)
}

// ── Right Panel: Session Detail ─────────────────────────────────────────────

@Composable
private fun SessionDetailPanel(
    session: LiveRouteSession?,
    messageText: String,
    onMessageTextChange: (String) -> Unit,
    onSendMessage: (Int, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (session == null) {
        // Empty state
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.MyLocation,
                    contentDescription = null,
                    tint = AdminOnSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(48.dp),
                )
                Spacer(modifier = Modifier.height(AdminTheme.spacing.md))
                Text(
                    text = "Select a session",
                    style = MaterialTheme.typography.titleMedium,
                    color = AdminOnSurfaceVariant,
                )
                Text(
                    text = "Choose an active session from the list to view details",
                    style = MaterialTheme.typography.bodySmall,
                    color = AdminOnSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }
        return
    }

    Column(modifier = modifier.padding(AdminTheme.spacing.lg)) {
        // ── Top Section: Worker Info Card ────────────────────────────────
        WorkerInfoCard(session)

        Spacer(modifier = Modifier.height(AdminTheme.spacing.md))

        // ── Middle Section: Map Placeholder ─────────────────────────────
        MapPlaceholder(
            session = session,
            modifier = Modifier.fillMaxWidth().height(200.dp),
        )

        Spacer(modifier = Modifier.height(AdminTheme.spacing.md))

        // ── Bottom Section: Markers + Messages side by side ─────────────
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.md),
        ) {
            // Markers Feed (left 50%)
            MarkersFeed(
                markers = session.markers,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )

            // Messages (right 50%)
            MessagesPanel(
                messages = session.messages,
                messageText = messageText,
                onMessageTextChange = onMessageTextChange,
                onSend = { onSendMessage(session.id, messageText) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

// ── Worker Info Card ────────────────────────────────────────────────────────

@Composable
private fun WorkerInfoCard(session: LiveRouteSession) {
    val progress = if (session.checkpointsTotal > 0) {
        session.checkpointsCompleted.toFloat() / session.checkpointsTotal
    } else {
        0f
    }

    Surface(
        tonalElevation = AdminTheme.elevation.card,
        shape = RoundedCornerShape(AdminTheme.spacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(AdminTheme.spacing.md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.sm),
                ) {
                    Text(
                        text = session.workerName,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    SessionStatusBadge(session.status)
                }
                Spacer(modifier = Modifier.height(AdminTheme.spacing.xxs))
                Text(
                    text = "${session.routeDisplayName}  (${session.routeCode})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AdminOnSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(AdminTheme.spacing.xs))

                val startTime = session.startedAt
                    .toLocalDateTime(TimeZone.currentSystemDefault())
                Text(
                    text = "Started at ${formatTime(startTime)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AdminOnSurfaceVariant,
                )
            }

            // Checkpoint progress ring-style summary
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${session.checkpointsCompleted}/${session.checkpointsTotal}",
                    style = MaterialTheme.typography.headlineSmall,
                    color = AdminPrimary,
                )
                Text(
                    text = "checkpoints",
                    style = MaterialTheme.typography.labelSmall,
                    color = AdminOnSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(AdminTheme.spacing.xs))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.width(100.dp).height(6.dp),
                    color = AdminPrimary,
                    trackColor = AdminOutline.copy(alpha = 0.3f),
                )
            }
        }
    }
}

// ── Map Placeholder ─────────────────────────────────────────────────────────

@Composable
private fun MapPlaceholder(session: LiveRouteSession, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(AdminTheme.spacing.sm))
            .border(
                width = 1.dp,
                color = AdminOutline,
                shape = RoundedCornerShape(AdminTheme.spacing.sm),
            )
            .background(AdminSurface.copy(alpha = 0.5f)),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.MyLocation,
                contentDescription = null,
                tint = AdminOnSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(34.dp),
            )
            Spacer(modifier = Modifier.height(AdminTheme.spacing.sm))
            Text(
                text = "Live map will render here",
                style = MaterialTheme.typography.bodyMedium,
                color = AdminOnSurfaceVariant,
            )
            session.currentPosition?.let { pos ->
                Text(
                    text = "Current: ${String.format("%.4f", pos.lat)}, ${String.format("%.4f", pos.lng)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AdminOnSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }
    }
}

// ── Markers Feed ────────────────────────────────────────────────────────────

@Composable
private fun MarkersFeed(
    markers: List<RouteMarker>,
    modifier: Modifier = Modifier,
) {
    Surface(
        tonalElevation = AdminTheme.elevation.card,
        shape = RoundedCornerShape(AdminTheme.spacing.sm),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Text(
                text = "Markers (${markers.size})",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(AdminTheme.spacing.md),
            )
            HorizontalDivider(color = AdminOutline.copy(alpha = 0.3f))

            if (markers.isEmpty()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Text(
                        text = "No markers yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AdminOnSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(AdminTheme.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(AdminTheme.spacing.xs),
                ) {
                    items(markers, key = { it.id }) { marker ->
                        MarkerItem(marker)
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkerItem(marker: RouteMarker) {
    val (icon, iconColor) = markerTypeIcon(marker.type)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AdminTheme.spacing.xs))
            .background(AdminSurface.copy(alpha = 0.3f))
            .padding(AdminTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.sm),
    ) {
        // Type icon
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(AdminTheme.spacing.xs))
                .background(iconColor.copy(alpha = 0.15f)),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = marker.type.name,
                tint = iconColor,
                modifier = Modifier.size(18.dp),
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = marker.type.name.lowercase().replaceFirstChar { it.uppercase() }
                    .replace("_", " "),
                style = MaterialTheme.typography.labelMedium,
                color = iconColor,
            )
            marker.note?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val time = marker.createdAt
                .toLocalDateTime(TimeZone.currentSystemDefault())
            Text(
                text = formatTime(time),
                style = MaterialTheme.typography.labelSmall,
                color = AdminOnSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}

private fun markerTypeIcon(type: RouteMarkerType): Pair<ImageVector, Color> {
    return when (type) {
        RouteMarkerType.NOTE -> Icons.Default.Description to AdminOnSurfaceVariant
        RouteMarkerType.PHOTO -> Icons.Default.CameraAlt to Color(0xFF3B82F6)
        RouteMarkerType.VIDEO -> Icons.Default.Videocam to Color(0xFF8B5CF6)
        RouteMarkerType.ISSUE -> Icons.Default.Warning to AdminWarning
        RouteMarkerType.BOUNTY_RESPONSE -> Icons.Default.Star to Color(0xFFF59E0B)
    }
}

// ── Messages Panel ──────────────────────────────────────────────────────────

@Composable
private fun MessagesPanel(
    messages: List<RouteMessage>,
    messageText: String,
    onMessageTextChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        tonalElevation = AdminTheme.elevation.card,
        shape = RoundedCornerShape(AdminTheme.spacing.sm),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Text(
                text = "Messages (${messages.size})",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(AdminTheme.spacing.md),
            )
            HorizontalDivider(color = AdminOutline.copy(alpha = 0.3f))

            // Message list
            val listState = rememberLazyListState()

            // Auto-scroll to bottom when new messages arrive
            LaunchedEffect(messages.size) {
                if (messages.isNotEmpty()) {
                    listState.animateScrollToItem(messages.lastIndex)
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = AdminTheme.spacing.sm, vertical = AdminTheme.spacing.xs),
                verticalArrangement = Arrangement.spacedBy(AdminTheme.spacing.sm),
            ) {
                if (messages.isEmpty()) {
                    item {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = AdminTheme.spacing.xl),
                        ) {
                            Text(
                                text = "No messages yet",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AdminOnSurfaceVariant,
                            )
                        }
                    }
                }
                items(messages, key = { it.id }) { message ->
                    MessageBubble(message)
                }
            }

            HorizontalDivider(color = AdminOutline.copy(alpha = 0.3f))

            // Input row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AdminTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AdminTheme.spacing.sm),
            ) {
                OutlinedTextField(
                    value = messageText,
                    onValueChange = onMessageTextChange,
                    placeholder = {
                        Text(
                            text = "Type a message...",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AdminPrimary,
                        unfocusedBorderColor = AdminOutline,
                    ),
                )
                IconButton(
                    onClick = onSend,
                    enabled = messageText.isNotBlank(),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send message",
                        tint = if (messageText.isNotBlank()) AdminPrimary else AdminOnSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: RouteMessage) {
    val isAdmin = message.senderRole == "admin"
    val alignment = if (isAdmin) Alignment.CenterEnd else Alignment.CenterStart
    val bubbleColor = if (isAdmin) {
        AdminPrimary.copy(alpha = 0.15f)
    } else {
        AdminSurface
    }
    val bubbleShape = RoundedCornerShape(
        topStart = 12.dp,
        topEnd = 12.dp,
        bottomStart = if (isAdmin) 12.dp else 4.dp,
        bottomEnd = if (isAdmin) 4.dp else 12.dp,
    )

    Box(
        contentAlignment = alignment,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(bubbleShape)
                .background(bubbleColor)
                .padding(horizontal = AdminTheme.spacing.md, vertical = AdminTheme.spacing.sm),
        ) {
            Text(
                text = message.senderName,
                style = MaterialTheme.typography.labelSmall,
                color = if (isAdmin) AdminPrimary else AdminOnSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(2.dp))
            val time = message.createdAt
                .toLocalDateTime(TimeZone.currentSystemDefault())
            Text(
                text = formatTime(time),
                style = MaterialTheme.typography.labelSmall,
                color = AdminOnSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}

// ── Utilities ───────────────────────────────────────────────────────────────

private fun formatTime(dateTime: kotlinx.datetime.LocalDateTime): String {
    val hour = dateTime.hour
    val minute = dateTime.minute.toString().padStart(2, '0')
    val amPm = if (hour < 12) "AM" else "PM"
    val displayHour = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }
    return "$displayHour:$minute $amPm"
}
