package com.gcc.app.ui.screens.admin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.components.StatusChip
import com.gcc.app.ui.theme.*
import com.gcc.shared.model.AdminReleaseBatchDto
import com.gcc.shared.model.AdminReleaseBatchDetailDto
import com.gcc.shared.model.AdminReleaseRouteDto
import com.gcc.shared.model.AdminRouteOfferDto
import com.gcc.shared.model.ReleaseBatchStatus
import com.gcc.shared.model.ReleaseRouteStatus
import com.gcc.shared.model.RouteOfferStatus
import kotlinx.datetime.Instant

/**
 * Release-queue admin surface (route-admin v2): batch list → batch detail
 * with position-ordered route slots (up/down reorder), the offer log, and a
 * cancel-batch action. All mutations round-trip through the server — the
 * ViewModel refetches list/detail after each one.
 */
@Composable
internal fun AdminReleaseQueueTab(
    batches: List<AdminReleaseBatchDto>,
    selectedBatchId: Int?,
    detail: AdminReleaseBatchDetailDto?,
    isDetailLoading: Boolean,
    actionError: String?,
    onOpenBatch: (Int) -> Unit,
    onCloseBatch: () -> Unit,
    onMoveRoute: (batchId: Int, releaseRouteId: Int, delta: Int) -> Unit,
    onCancelBatch: (batchId: Int, onResult: (Boolean, String?) -> Unit) -> Unit,
    onDismissError: () -> Unit,
) {
    val openBatchId = selectedBatchId
    if (openBatchId == null) {
        ReleaseBatchList(
            batches = batches,
            onOpenBatch = onOpenBatch,
        )
    } else {
        ReleaseBatchDetail(
            batchId = openBatchId,
            detail = detail,
            isLoading = isDetailLoading,
            actionError = actionError,
            onBack = onCloseBatch,
            onMoveRoute = onMoveRoute,
            onCancelBatch = onCancelBatch,
            onDismissError = onDismissError,
        )
    }
}

// ── Batch list ───────────────────────────────────────────────────────────────

@Composable
private fun ReleaseBatchList(
    batches: List<AdminReleaseBatchDto>,
    onOpenBatch: (Int) -> Unit,
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(vertical = 10.dp),
    ) {
        if (batches.isEmpty()) {
            item {
                Box(
                    Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "\uD83D\uDEA6\nNo release batches yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        items(batches, key = { it.id }) { batch ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                batch.name ?: "Batch #${batch.id}",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                buildString {
                                    append(batch.communityName ?: "Service area ${batch.serviceAreaId}")
                                    append(" \u2022 releases ")
                                    append(batch.releaseAt.fmtDateTime())
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        ReleaseBatchStatusChip(batch.status)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${batch.routeCount} routes \u2022 ${batch.openSlots} open \u2022 " +
                                "${batch.pendingOffers} offers \u2022 wave ${batch.waveNumber}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            onClick = { onOpenBatch(batch.id) },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            Text("Open", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

// ── Batch detail ─────────────────────────────────────────────────────────────

@Composable
private fun ReleaseBatchDetail(
    batchId: Int,
    detail: AdminReleaseBatchDetailDto?,
    isLoading: Boolean,
    actionError: String?,
    onBack: () -> Unit,
    onMoveRoute: (batchId: Int, releaseRouteId: Int, delta: Int) -> Unit,
    onCancelBatch: (batchId: Int, onResult: (Boolean, String?) -> Unit) -> Unit,
    onDismissError: () -> Unit,
) {
    var showCancelConfirm by remember(batchId) { mutableStateOf(false) }
    val sortedRoutes = remember(detail) { detail?.routes?.sortedBy { it.position } ?: emptyList() }
    val batch = detail?.batch
    val cancellable = batch?.status in listOf(
        ReleaseBatchStatus.DRAFT,
        ReleaseBatchStatus.SCHEDULED,
        ReleaseBatchStatus.ACTIVE,
    )

    if (showCancelConfirm && batch != null) {
        AlertDialog(
            onDismissRequest = { showCancelConfirm = false },
            title = { Text("Cancel batch", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Cancel ${batch.name ?: "batch #${batch.id}"}? Open route slots and pending " +
                        "offers will be cancelled. Claimed routes are not affected.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onCancelBatch(batchId) { success, _ ->
                            if (success) showCancelConfirm = false
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = GccError),
                ) {
                    Text("Cancel batch", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelConfirm = false }) { Text("Keep") }
            },
        )
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(vertical = 10.dp),
    ) {
        // ── Header ────────────────────────────────────────────────────
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(onClick = onBack, shape = RoundedCornerShape(8.dp)) {
                    Text("\u2190 Batches", style = MaterialTheme.typography.labelMedium)
                }
                if (batch != null) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            batch.name ?: "Batch #${batch.id}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "Releases ${batch.releaseAt.fmtDateTime()} \u2022 wave ${batch.waveNumber}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ReleaseBatchStatusChip(batch.status)
                }
            }
        }

        actionError?.let { message ->
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = GccError.copy(alpha = 0.1f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = GccError,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onDismissError) { Text("Dismiss") }
                    }
                }
            }
        }

        if (isLoading && detail == null) {
            item {
                Box(
                    Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = GccSky)
                }
            }
        }

        // ── Route slots (position ordered, up/down reorder) ───────────
        item {
            Text(
                "Route queue (${sortedRoutes.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        items(sortedRoutes, key = { it.id }) { slot ->
            val index = sortedRoutes.indexOfFirst { it.id == slot.id }
            ReleaseRouteRow(
                slot = slot,
                canMoveUp = index > 0,
                canMoveDown = index in 0 until sortedRoutes.size - 1,
                onMoveUp = { onMoveRoute(batchId, slot.id, -1) },
                onMoveDown = { onMoveRoute(batchId, slot.id, +1) },
            )
        }

        // ── Offers ────────────────────────────────────────────────────
        val offers = detail?.offers ?: emptyList()
        item {
            Spacer(Modifier.height(6.dp))
            Text(
                "Offers (${offers.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (offers.isEmpty()) {
            item {
                Text(
                    "No offers sent yet — the next wave creates them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        items(offers, key = { it.id }) { offer ->
            ReleaseOfferRow(offer)
        }

        // ── Cancel batch ──────────────────────────────────────────────
        if (cancellable) {
            item {
                TextButton(
                    onClick = { showCancelConfirm = true },
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    Text("Cancel batch", color = GccError, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun ReleaseRouteRow(
    slot: AdminReleaseRouteDto,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "#${slot.position}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    slot.routeName ?: slot.routeCode ?: "Route ${slot.routeId}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "route ${slot.routeId} \u2022 job ${slot.jobId}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ReleaseRouteStatusChip(slot.status)
            Column {
                TextButton(
                    onClick = onMoveUp,
                    enabled = canMoveUp,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.semantics {
                        contentDescription = "Move ${slot.routeName ?: "route ${slot.routeId}"} up"
                    },
                ) { Text("\u2191") }
                TextButton(
                    onClick = onMoveDown,
                    enabled = canMoveDown,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.semantics {
                        contentDescription = "Move ${slot.routeName ?: "route ${slot.routeId}"} down"
                    },
                ) { Text("\u2193") }
            }
        }
    }
}

@Composable
private fun ReleaseOfferRow(offer: AdminRouteOfferDto) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    offer.workerName ?: "Worker ${offer.workerId}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "wave ${offer.waveNumber} \u2022 expires ${offer.expiresAt.fmtDateTime()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RouteOfferStatusChip(offer.status)
        }
    }
}

// ── Status chips ─────────────────────────────────────────────────────────────

@Composable
private fun ReleaseBatchStatusChip(status: ReleaseBatchStatus) {
    val (label, color) = when (status) {
        ReleaseBatchStatus.DRAFT -> "Draft" to Color.Gray
        ReleaseBatchStatus.SCHEDULED -> "Scheduled" to GccSky
        ReleaseBatchStatus.ACTIVE -> "Active" to GccGreen
        ReleaseBatchStatus.COMPLETED -> "Completed" to GccBlue
        ReleaseBatchStatus.CANCELLED -> "Cancelled" to GccError
    }
    StatusChip(text = label, dotColor = color, backgroundColor = color.copy(alpha = 0.15f))
}

@Composable
private fun ReleaseRouteStatusChip(status: ReleaseRouteStatus) {
    val (label, color) = when (status) {
        ReleaseRouteStatus.OPEN -> "Open" to GccAvailable
        ReleaseRouteStatus.CLAIMED -> "Claimed" to GccClaimed
        ReleaseRouteStatus.CANCELLED -> "Cancelled" to GccError
    }
    StatusChip(text = label, dotColor = color, backgroundColor = color.copy(alpha = 0.15f))
}

@Composable
private fun RouteOfferStatusChip(status: RouteOfferStatus) {
    val (label, color) = when (status) {
        RouteOfferStatus.PENDING -> "Pending" to GccPendingReview
        RouteOfferStatus.ACCEPTED -> "Accepted" to GccGreen
        RouteOfferStatus.DECLINED -> "Declined" to GccClaimed
        RouteOfferStatus.EXPIRED -> "Expired" to Color.Gray
        RouteOfferStatus.CANCELLED -> "Cancelled" to GccError
    }
    StatusChip(text = label, dotColor = color, backgroundColor = color.copy(alpha = 0.15f))
}

/** "2026-08-21T14:00:00Z" → "2026-08-21 14:00 UTC" (minute precision is all
 *  the admin needs here; queue times are server-rendered UTC). */
private fun Instant?.fmtDateTime(): String =
    this?.toString()?.replace('T', ' ')?.take(16)?.plus(" UTC") ?: "\u2014"
