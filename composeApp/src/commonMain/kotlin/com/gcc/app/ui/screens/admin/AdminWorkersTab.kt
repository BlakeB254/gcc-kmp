package com.gcc.app.ui.screens.admin

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.theme.*
import com.gcc.shared.model.BulkAction
import com.gcc.shared.model.VerificationStatus
import com.gcc.shared.model.Worker

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AdminWorkersTab(
    workers: List<Worker>,
    onWorkerClick: (workerId: Int) -> Unit = {},
    onBulkAction: (workerIds: List<Int>, action: BulkAction) -> Unit = { _, _ -> },
) {
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<Int>()) }

    // Exit selection mode when workers list changes (after bulk action completes)
    LaunchedEffect(workers) {
        if (selectionMode && selectedIds.isNotEmpty()) {
            // Keep selection mode but clear stale IDs
            selectedIds = selectedIds.filter { id -> workers.any { it.id == id } }.toSet()
        }
    }

    var searchQuery by remember { mutableStateOf("") }
    val filteredWorkers = remember(workers, searchQuery) {
        if (searchQuery.isBlank()) workers
        else {
            val q = searchQuery.lowercase()
            workers.filter { it.name.lowercase().contains(q) || it.email.lowercase().contains(q) }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Search bar ───────────────────────────────────────────────
        AdminSearchBar(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            placeholder = "Search workers...",
        )

        // ── Bulk action bar ──────────────────────────────────────────
        AnimatedVisibility(
            visible = selectionMode,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Surface(
                color = GccGoldDark.copy(alpha = 0.1f),
                tonalElevation = 2.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${selectedIds.size} selected",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = {
                            onBulkAction(selectedIds.toList(), BulkAction.VERIFY)
                            selectionMode = false
                            selectedIds = emptySet()
                        },
                        enabled = selectedIds.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = GccGreen),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text("Verify All", style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onBulkAction(selectedIds.toList(), BulkAction.SUSPEND)
                            selectionMode = false
                            selectedIds = emptySet()
                        },
                        enabled = selectedIds.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = GccError),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text("Suspend All", style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        selectionMode = false
                        selectedIds = emptySet()
                    }) {
                        Text("Cancel")
                    }
                }
            }
        }

        // ── Worker list ──────────────────────────────────────────────
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                Text(
                    if (searchQuery.isBlank()) "${workers.size} workers"
                    else "${filteredWorkers.size} of ${workers.size} workers",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            items(filteredWorkers, key = { it.id }) { worker ->
                val verified = worker.verification.status == VerificationStatus.APPROVED
                val statusColor = when (worker.verification.status) {
                    VerificationStatus.APPROVED -> GccGreen
                    VerificationStatus.PENDING -> GccGold
                    VerificationStatus.REJECTED -> GccError
                    VerificationStatus.NEEDS_INFO -> GccBlue
                }
                val isSelected = selectedIds.contains(worker.id)

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (selectionMode && isSelected) {
                                Modifier.border(2.dp, GccGold, RoundedCornerShape(8.dp))
                            } else {
                                Modifier
                            }
                        )
                        .combinedClickable(
                            onClick = {
                                if (selectionMode) {
                                    selectedIds = if (isSelected) {
                                        selectedIds - worker.id
                                    } else {
                                        selectedIds + worker.id
                                    }
                                    if (selectedIds.isEmpty()) {
                                        selectionMode = false
                                    }
                                } else {
                                    onWorkerClick(worker.id)
                                }
                            },
                            onLongClick = {
                                if (!selectionMode) {
                                    selectionMode = true
                                    selectedIds = setOf(worker.id)
                                }
                            },
                        ),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Selection checkbox
                        if (selectionMode) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { checked ->
                                    selectedIds = if (checked) {
                                        selectedIds + worker.id
                                    } else {
                                        selectedIds - worker.id
                                    }
                                    if (selectedIds.isEmpty()) {
                                        selectionMode = false
                                    }
                                },
                                colors = CheckboxDefaults.colors(checkedColor = GccGold),
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(worker.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text(
                                worker.email,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = statusColor.copy(alpha = 0.15f),
                        ) {
                            Text(
                                if (verified) "Verified" else worker.verification.status.name.lowercase()
                                    .replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.labelSmall,
                                color = statusColor,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            worker.role,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (worker.role == "admin") GccGold else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}
