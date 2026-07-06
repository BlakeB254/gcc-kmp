package com.gcc.app.ui.screens.admin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.map.GccMap
import com.gcc.app.ui.map.boundaryFitZoom
import com.gcc.app.ui.map.mapOverlayZoomBias
import com.gcc.app.ui.theme.GccGreen
import com.gcc.shared.model.BlockProposal
import com.gcc.shared.model.Coordinates

/**
 * Review queue for AI-generated block candidates — see the
 * admin/block-proposals API routes on the server. Nothing a proposal
 * describes is live until it's approved here, which inserts a real Block
 * on the server (mirrors the web admin's /admin/block-proposals page).
 */
@Composable
internal fun AdminBlockProposalsTab(
    proposals: List<BlockProposal>,
    actionError: String?,
    onApprove: (proposalId: Int, onResult: (success: Boolean, error: String?) -> Unit) -> Unit,
    onReject: (proposalId: Int, reason: String?, onResult: (success: Boolean, error: String?) -> Unit) -> Unit,
    onDismissActionError: () -> Unit,
) {
    var rejectTargetId by remember { mutableStateOf<Int?>(null) }
    var rejectReason by remember { mutableStateOf("") }
    var actionInProgress by remember { mutableStateOf(false) }

    if (proposals.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "🧱\nNo pending block proposals",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(proposals, key = { it.id }) { proposal ->
            BlockProposalCard(
                proposal = proposal,
                actionInProgress = actionInProgress,
                onApprove = {
                    actionInProgress = true
                    onApprove(proposal.id) { _, _ -> actionInProgress = false }
                },
                onRejectClick = {
                    rejectTargetId = proposal.id
                    rejectReason = ""
                },
            )
        }
    }

    rejectTargetId?.let { proposalId ->
        AlertDialog(
            onDismissRequest = { rejectTargetId = null; rejectReason = "" },
            title = { Text("Reject Proposal #$proposalId") },
            text = {
                Column {
                    Text(
                        "Optional reason (e.g. crosses a park boundary):",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = rejectReason,
                        onValueChange = { rejectReason = it },
                        label = { Text("Reason") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = !actionInProgress,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        val reason = rejectReason.trim().ifBlank { null }
                        actionInProgress = true
                        onReject(proposalId, reason) { _, _ ->
                            actionInProgress = false
                            rejectTargetId = null
                            rejectReason = ""
                        }
                    },
                ) { Text("Reject") }
            },
            dismissButton = {
                TextButton(onClick = { rejectTargetId = null; rejectReason = "" }) { Text("Cancel") }
            },
        )
    }

    actionError?.let { error ->
        AlertDialog(
            onDismissRequest = onDismissActionError,
            title = { Text("Action Failed") },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = onDismissActionError) { Text("OK") }
            },
        )
    }
}

@Composable
private fun BlockProposalCard(
    proposal: BlockProposal,
    actionInProgress: Boolean,
    onApprove: () -> Unit,
    onRejectClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = proposal.codeHint ?: "Proposal #${proposal.id}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    if (proposal.fromCorner != null && proposal.toCorner != null) {
                        Text(
                            text = "${proposal.fromCorner} → ${proposal.toCorner}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = "${proposal.exclusionZoneCount} exclusion zones",
                    style = MaterialTheme.typography.labelMedium,
                    color = GccGreen,
                )
            }

            ProposalBoundaryPreview(
                proposal = proposal,
                modifier = Modifier.padding(top = 12.dp),
            )

            proposal.streetName?.let { streetName ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = streetName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            proposal.generationBatch?.let { batch ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Source: $batch",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onRejectClick,
                    enabled = !actionInProgress,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Reject") }
                Button(
                    onClick = onApprove,
                    enabled = !actionInProgress,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = GccGreen),
                ) { Text("Approve") }
            }
        }
    }
}

/**
 * Non-interactive minimap framed to the proposal's boundary so the
 * approve/reject decision isn't blind — the ring renders through
 * [GccMap.routeCoordinates] with the same styling an approved block gets
 * on the real map. Per-card `GccMap` in a LazyColumn is the established
 * minimap pattern (BountyListScreen, WalletScreen); only visible cards
 * hold a map instance.
 */
@Composable
private fun ProposalBoundaryPreview(
    proposal: BlockProposal,
    modifier: Modifier = Modifier,
) {
    // GeoJSON Point is [lng, lat] — Coordinates(lng, lat) is positional.
    val ring = remember(proposal.id) {
        proposal.boundary.coordinates.firstOrNull().orEmpty()
            .filter { it.size >= 2 }
            .map { pt -> Coordinates(pt[0], pt[1]) }
    }
    if (ring.size < 3) return

    val loLat = ring.minOf { it.lat }
    val hiLat = ring.maxOf { it.lat }
    val loLng = ring.minOf { it.lng }
    val hiLng = ring.maxOf { it.lng }
    val centerLat = (loLat + hiLat) / 2.0
    val centerLng = (loLng + hiLng) / 2.0

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(RoundedCornerShape(8.dp)),
    ) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val zoom = boundaryFitZoom(
            latSpanDeg = hiLat - loLat,
            lngSpanDeg = hiLng - loLng,
            centerLat = centerLat,
            widthPx = widthPx,
            heightPx = heightPx,
            zoomBias = mapOverlayZoomBias(density.density),
        ).coerceIn(13.0, 17.5)

        GccMap(
            modifier = Modifier.fillMaxSize(),
            centerLat = centerLat,
            centerLng = centerLng,
            zoom = zoom,
            enableGestures = false,
            routeCoordinates = listOf(ring),
            showBuildings = false,
            instantCamera = true,
            innerBoundaryFallback = false,
            showGridLines = false,
        )
    }
}
