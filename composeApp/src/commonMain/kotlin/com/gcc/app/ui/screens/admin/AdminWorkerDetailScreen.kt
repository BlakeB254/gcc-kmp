package com.gcc.app.ui.screens.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.components.NetworkImage
import com.gcc.app.ui.theme.*
import com.gcc.app.viewmodel.AdminPanelViewModel
import com.gcc.shared.model.DocumentMedia
import com.gcc.shared.model.DocumentStatus
import com.gcc.shared.model.VerificationStatus
import com.gcc.shared.model.Worker
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminWorkerDetailScreen(
    workerId: Int,
    onBack: () -> Unit,
) {
    val viewModel = koinInject<AdminPanelViewModel>()
    val worker by viewModel.selectedWorker.collectAsState()
    val documents by viewModel.workerDocuments.collectAsState()
    val isLoading by viewModel.workerActionLoading.collectAsState()
    val uiState by viewModel.uiState.collectAsState()

    var reviewNotes by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var fullScreenImageUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(workerId) {
        viewModel.loadWorkerDetail(workerId)
    }

    // Sync review notes when worker loads
    LaunchedEffect(worker) {
        worker?.verification?.reviewNotes?.let { reviewNotes = it }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(worker?.name ?: "Worker #$workerId") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("\u2190 Back", color = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = GccSkyDark,
                    titleContentColor = Color.White,
                ),
            )
        },
    ) { padding ->
        when {
            isLoading && worker == null -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = GccSky)
            }
            worker == null -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text("Worker not found")
            }
            else -> {
                val w = worker!!
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Worker info card
                    item { WorkerInfoCard(w) }

                    // Overall verification status
                    item { OverallVerificationCard(w) }

                    // Government ID document
                    item {
                        DocumentCard(
                            title = "Government ID",
                            status = w.verification.governmentIdStatus,
                            documentMedia = documents?.governmentId,
                            onImageClick = { url -> fullScreenImageUrl = url },
                            onApprove = {
                                viewModel.verifyWorker(
                                    workerId = w.id,
                                    status = computeOverallStatus(DocumentStatus.APPROVED, w.verification.proofOfAddressStatus),
                                    governmentIdStatus = DocumentStatus.APPROVED,
                                    reviewNotes = reviewNotes.ifBlank { null },
                                )
                            },
                            onReject = {
                                viewModel.verifyWorker(
                                    workerId = w.id,
                                    status = VerificationStatus.REJECTED,
                                    governmentIdStatus = DocumentStatus.REJECTED,
                                    reviewNotes = reviewNotes.ifBlank { null },
                                )
                            },
                            onRequestResubmit = {
                                viewModel.verifyWorker(
                                    workerId = w.id,
                                    status = VerificationStatus.NEEDS_INFO,
                                    governmentIdStatus = DocumentStatus.RESUBMIT,
                                    reviewNotes = reviewNotes.ifBlank { null },
                                )
                            },
                            isLoading = isLoading,
                        )
                    }

                    // Proof of Address document
                    item {
                        DocumentCard(
                            title = "Proof of Address",
                            status = w.verification.proofOfAddressStatus,
                            documentMedia = documents?.proofOfAddress,
                            onImageClick = { url -> fullScreenImageUrl = url },
                            onApprove = {
                                viewModel.verifyWorker(
                                    workerId = w.id,
                                    status = computeOverallStatus(w.verification.governmentIdStatus, DocumentStatus.APPROVED),
                                    proofOfAddressStatus = DocumentStatus.APPROVED,
                                    reviewNotes = reviewNotes.ifBlank { null },
                                )
                            },
                            onReject = {
                                viewModel.verifyWorker(
                                    workerId = w.id,
                                    status = VerificationStatus.REJECTED,
                                    proofOfAddressStatus = DocumentStatus.REJECTED,
                                    reviewNotes = reviewNotes.ifBlank { null },
                                )
                            },
                            onRequestResubmit = {
                                viewModel.verifyWorker(
                                    workerId = w.id,
                                    status = VerificationStatus.NEEDS_INFO,
                                    proofOfAddressStatus = DocumentStatus.RESUBMIT,
                                    reviewNotes = reviewNotes.ifBlank { null },
                                )
                            },
                            isLoading = isLoading,
                        )
                    }

                    // Review notes
                    item {
                        Text(
                            "Review Notes",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = reviewNotes,
                            onValueChange = { reviewNotes = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Add notes about this worker's verification...") },
                            minLines = 3,
                            maxLines = 6,
                            shape = RoundedCornerShape(8.dp),
                        )
                    }

                    // Quick actions
                    item {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        Text("Quick Actions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (w.verification.status != VerificationStatus.APPROVED) {
                                Button(
                                    onClick = {
                                        viewModel.verifyWorker(
                                            workerId = w.id,
                                            status = VerificationStatus.APPROVED,
                                            governmentIdStatus = DocumentStatus.APPROVED,
                                            proofOfAddressStatus = DocumentStatus.APPROVED,
                                            reviewNotes = reviewNotes.ifBlank { null },
                                        )
                                    },
                                    modifier = Modifier.weight(1f),
                                    enabled = !isLoading,
                                    colors = ButtonDefaults.buttonColors(containerColor = GccGreen),
                                ) { Text("Approve All") }
                            }
                            if (w.verification.status != VerificationStatus.REJECTED) {
                                OutlinedButton(
                                    onClick = {
                                        viewModel.verifyWorker(
                                            workerId = w.id,
                                            status = VerificationStatus.REJECTED,
                                            governmentIdStatus = DocumentStatus.REJECTED,
                                            proofOfAddressStatus = DocumentStatus.REJECTED,
                                            reviewNotes = reviewNotes.ifBlank { null },
                                        )
                                    },
                                    modifier = Modifier.weight(1f),
                                    enabled = !isLoading,
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = GccError),
                                ) { Text("Reject All") }
                            }
                        }
                    }

                    // Loading indicator for actions
                    if (isLoading) {
                        item {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = GccSky,
                            )
                        }
                    }

                    // Error
                    uiState.error?.let { error ->
                        item {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                color = GccError.copy(alpha = 0.1f),
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        error,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = GccError,
                                    )
                                    TextButton(onClick = { viewModel.clearError() }) {
                                        Text("Dismiss", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }

                    // Delete worker
                    item {
                        Spacer(Modifier.height(16.dp))
                        if (showDeleteConfirm) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                color = GccError.copy(alpha = 0.1f),
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        "Permanently delete this worker account?",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = GccError,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(
                                            onClick = { showDeleteConfirm = false },
                                            modifier = Modifier.weight(1f),
                                        ) { Text("Cancel") }
                                        Button(
                                            onClick = {
                                                viewModel.deleteWorker(workerId) { onBack() }
                                            },
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.buttonColors(containerColor = GccError),
                                        ) { Text("Delete") }
                                    }
                                }
                            }
                        } else {
                            TextButton(onClick = { showDeleteConfirm = true }) {
                                Text("Delete Worker", color = GccError, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }

    // Full-screen image viewer overlay
    fullScreenImageUrl?.let { url ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.95f))
                .clickable { fullScreenImageUrl = null },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                NetworkImage(
                    url = url,
                    contentDescription = "Document full view",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Fit,
                )
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = { fullScreenImageUrl = null }) {
                    Text("Tap anywhere to close", color = Color.White.copy(alpha = 0.7f))
                }
            }
        }
    }
}

// ─── Helper composables ────────────────────────────────────────────

@Composable
private fun WorkerInfoCard(worker: Worker) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(worker.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            InfoRow("Email", worker.email)
            InfoRow("Phone", worker.phone)
            InfoRow("Address", buildString {
                if (worker.address.street.isNotBlank()) append("${worker.address.street}, ")
                append("${worker.address.city}, ${worker.address.state}")
                if (worker.address.zip.isNotBlank()) append(" ${worker.address.zip}")
            })
            InfoRow("Role", worker.role)
            InfoRow("Tier", worker.earlyAdopterTier.name.lowercase().replaceFirstChar { it.uppercase() })
            InfoRow("Jobs Completed", worker.stats.jobsCompleted.toString())
            if (worker.stats.totalEarnings > 0) {
                val dollars = worker.stats.totalEarnings / 100
                val cents = (worker.stats.totalEarnings % 100).toString().padStart(2, '0')
                InfoRow("Total Earnings", "$$dollars.$cents")
            }
            worker.stats.averageRating?.let { rating ->
                val rounded = (rating * 10).toInt() / 10.0
                InfoRow("Rating", "$rounded / 5.0")
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun OverallVerificationCard(worker: Worker) {
    val status = worker.verification.status
    val statusColor = when (status) {
        VerificationStatus.APPROVED -> GccGreen
        VerificationStatus.PENDING -> GccSky
        VerificationStatus.REJECTED -> GccError
        VerificationStatus.NEEDS_INFO -> GccBlue
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = statusColor.copy(alpha = 0.1f),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Overall Verification", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    when (status) {
                        VerificationStatus.APPROVED -> "Approved"
                        VerificationStatus.PENDING -> "Pending Review"
                        VerificationStatus.REJECTED -> "Rejected"
                        VerificationStatus.NEEDS_INFO -> "Needs Info"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = statusColor,
                )
            }
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = statusColor.copy(alpha = 0.2f),
            ) {
                Text(
                    when (status) {
                        VerificationStatus.APPROVED -> "\u2713"
                        VerificationStatus.PENDING -> "\u23F3"
                        VerificationStatus.REJECTED -> "\u2717"
                        VerificationStatus.NEEDS_INFO -> "?"
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.titleLarge,
                    color = statusColor,
                )
            }
        }
    }
}

@Composable
private fun DocumentCard(
    title: String,
    status: DocumentStatus,
    documentMedia: DocumentMedia? = null,
    onImageClick: (String) -> Unit = {},
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onRequestResubmit: () -> Unit,
    isLoading: Boolean,
) {
    val statusColor = when (status) {
        DocumentStatus.APPROVED -> GccGreen
        DocumentStatus.PENDING -> GccSky
        DocumentStatus.REJECTED -> GccError
        DocumentStatus.RESUBMIT -> GccBlue
    }
    val statusLabel = when (status) {
        DocumentStatus.APPROVED -> "Approved"
        DocumentStatus.PENDING -> "Pending"
        DocumentStatus.REJECTED -> "Rejected"
        DocumentStatus.RESUBMIT -> "Resubmit Requested"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = statusColor.copy(alpha = 0.15f),
                ) {
                    Text(
                        statusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }

            // Document image preview
            if (documentMedia?.url != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onImageClick(documentMedia.url!!) },
                ) {
                    NetworkImage(
                        url = documentMedia.url!!,
                        contentDescription = title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                    // "Tap to view" label
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = Color.Black.copy(alpha = 0.6f),
                    ) {
                        Text(
                            "\uD83D\uDD0D Tap to view",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            } else {
                Surface(
                    modifier = Modifier.fillMaxWidth().height(60.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            "No document uploaded",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                        )
                    }
                }
            }

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (status != DocumentStatus.APPROVED) {
                    OutlinedButton(
                        onClick = onApprove,
                        modifier = Modifier.weight(1f),
                        enabled = !isLoading,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = GccGreen),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) { Text("Approve", style = MaterialTheme.typography.labelMedium) }
                }
                if (status != DocumentStatus.REJECTED) {
                    OutlinedButton(
                        onClick = onReject,
                        modifier = Modifier.weight(1f),
                        enabled = !isLoading,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = GccError),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) { Text("Reject", style = MaterialTheme.typography.labelMedium) }
                }
                if (status != DocumentStatus.RESUBMIT) {
                    OutlinedButton(
                        onClick = onRequestResubmit,
                        modifier = Modifier.weight(1f),
                        enabled = !isLoading,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = GccBlue),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) { Text("Resubmit", style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
    }
}

/** If both documents are approved, overall is approved. Otherwise pending. */
private fun computeOverallStatus(govId: DocumentStatus, proofOfAddress: DocumentStatus): VerificationStatus {
    return if (govId == DocumentStatus.APPROVED && proofOfAddress == DocumentStatus.APPROVED) {
        VerificationStatus.APPROVED
    } else {
        VerificationStatus.PENDING
    }
}
