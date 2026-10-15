package com.gcc.app.ui.screens.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.components.GccButton
import com.gcc.app.ui.components.GccButtonVariant
import com.gcc.app.ui.components.GccTextField
import com.gcc.app.ui.components.NetworkImage
import com.gcc.app.ui.theme.*
import com.gcc.app.viewmodel.AdminPanelViewModel
import com.gcc.shared.model.AdminGrantAction
import com.gcc.shared.model.DocumentMedia
import com.gcc.shared.model.DocumentStatus
import com.gcc.shared.model.PointsAction
import com.gcc.shared.model.PointsLedgerEntry
import com.gcc.shared.model.StripeOnboardingStatus
import com.gcc.shared.model.VerificationStatus
import com.gcc.shared.model.Worker
import kotlin.math.abs
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
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
    val pointsHistory by viewModel.workerPointsHistory.collectAsState()
    val pointsHistoryLoading by viewModel.workerPointsHistoryLoading.collectAsState()

    var reviewNotes by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var fullScreenImageUrl by remember { mutableStateOf<String?>(null) }
    var showGrantPointsDialog by remember { mutableStateOf(false) }
    var showSendPayoutDialog by remember { mutableStateOf(false) }
    var payoutResultMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(workerId) {
        viewModel.loadWorkerDetail(workerId)
        viewModel.loadWorkerPointsHistory(workerId)
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
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = GccSkyDark,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
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
                                    // Carry the OTHER document's current status. The server
                                    // gates approval on
                                    //   stored-document-id OR this-request's-status == approved
                                    // for each document. Sending only the card being approved
                                    // meant the second approval arrived with the other field
                                    // null, so the check fell back to the stored id — which is
                                    // absent whenever documents were never uploaded, and the
                                    // request 400'd. Describing the full intended state fixes it.
                                    proofOfAddressStatus = w.verification.proofOfAddressStatus,
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
                                    // Carry the OTHER document's current status. The server
                                    // gates approval on
                                    //   stored-document-id OR this-request's-status == approved
                                    // for each document. Sending only the card being approved
                                    // meant the second approval arrived with the other field
                                    // null, so the check fell back to the stored id — which is
                                    // absent whenever documents were never uploaded, and the
                                    // request 400'd. Describing the full intended state fixes it.
                                    governmentIdStatus = w.verification.governmentIdStatus,
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

                    // Community points + grant action
                    item {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        Text("Community Points", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${w.stats.communityPoints} pts",
                                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                                color = GccGreen,
                            )
                            Button(
                                onClick = { showGrantPointsDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = GccSky),
                                enabled = !isLoading,
                            ) { Text("Grant Points") }
                        }
                    }

                    // Wallet: balance + ad-hoc credit (bonus / correction / off-platform work)
                    item {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        Text("Wallet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Balance: ${com.gcc.shared.util.GccFormatters.formatCents(w.walletBalanceCents)}",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = GccGreen,
                                )
                                Text(
                                    payoutRoutingSummary(w),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted,
                                )
                            }
                            Button(
                                onClick = { showSendPayoutDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = GccGreen),
                                enabled = !isLoading,
                            ) { Text("Send Payout") }
                        }
                    }

                    // Recent points activity (audit trail)
                    item {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Recent Activity",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = TextMuted,
                        )
                    }
                    if (pointsHistoryLoading && pointsHistory.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = GccSky,
                                    strokeWidth = 2.dp,
                                )
                            }
                        }
                    } else if (pointsHistory.isEmpty()) {
                        item {
                            Text(
                                "No points activity yet.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSubtle,
                            )
                        }
                    } else {
                        items(pointsHistory.take(10)) { entry ->
                            PointsHistoryRow(entry)
                        }
                    }

                    // Quick actions
                    item {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        Text("Quick Actions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        val hasRequiredDocs = documents?.governmentId?.url != null &&
                            documents?.proofOfAddress?.url != null

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
                                    enabled = !isLoading && hasRequiredDocs,
                                    colors = ButtonDefaults.buttonColors(containerColor = GccGreen),
                                ) { Text(if (hasRequiredDocs) "Approve All" else "No Documents") }
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

    // Grant Points dialog
    if (showGrantPointsDialog && worker != null) {
        GrantPointsDialog(
            workerName = worker?.name ?: "Worker",
            isLoading = isLoading,
            onDismiss = { showGrantPointsDialog = false },
            onSubmit = { amount, reason, action ->
                viewModel.grantPointsToWorker(
                    workerId = workerId,
                    amount = amount,
                    reason = reason,
                    action = action,
                ) { success, _, _ ->
                    if (success) {
                        showGrantPointsDialog = false
                        // Refresh history so the new entry shows immediately
                        viewModel.loadWorkerPointsHistory(workerId)
                    }
                }
            },
        )
    }

    // Send Payout dialog
    if (showSendPayoutDialog && worker != null) {
        SendPayoutDialog(
            worker = worker!!,
            onDismiss = { showSendPayoutDialog = false },
            onSubmit = { amountCents, note, idempotencyKey, onDone ->
                viewModel.sendPayout(
                    workerId = workerId,
                    amountCents = amountCents,
                    note = note,
                    idempotencyKey = idempotencyKey,
                ) { success, message ->
                    onDone(success, message)
                    if (success) {
                        showSendPayoutDialog = false
                        payoutResultMessage = message
                    }
                }
            },
        )
    }

    // Payout result confirmation
    payoutResultMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { payoutResultMessage = null },
            title = { Text("Payout") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { payoutResultMessage = null }) { Text("OK") }
            },
        )
    }
}

/** One-line summary of where a payout to this worker would go. */
/** How this worker's cash-outs leave the platform (informational only — money
 *  lands in their wallet regardless). */
private fun payoutRoutingSummary(worker: Worker): String = when {
    worker.payoutMethod == "check" ->
        "Cashes out by mailed paper check"
    worker.stripe.onboardingStatus == StripeOnboardingStatus.COMPLETED ->
        "Cashes out by direct deposit (Stripe)"
    else ->
        "Hasn't set up direct deposit yet — can still be paid; funds wait in their wallet"
}

@Composable
private fun SendPayoutDialog(
    worker: Worker,
    onDismiss: () -> Unit,
    onSubmit: (
        amountCents: Int,
        note: String?,
        idempotencyKey: String,
        onDone: (success: Boolean, message: String?) -> Unit,
    ) -> Unit,
) {
    var amountText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    // One key per dialog open: a retried tap or flaky network can't double-send.
    val idempotencyKey = remember {
        val hex = "0123456789abcdef"
        buildString(32) { repeat(32) { append(hex[kotlin.random.Random.nextInt(16)]) } }
    }

    val amountCents = parsePayoutDollarsToCents(amountText)
    // Wallet-ledger rework: payouts credit the worker's in-app wallet, so
    // there is no Stripe-Connect prerequisite anymore.
    val canSubmit = !sending &&
        amountCents != null && amountCents > 0 && amountCents <= MAX_ADHOC_PAYOUT_CENTS

    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text("Send Payout") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "The money lands in ${worker.name}'s GCC wallet instantly — even if they haven't set up Stripe. They cash out from the app by direct deposit or paper check.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                )

                GccTextField(
                    value = amountText,
                    onValueChange = { input ->
                        if (input.isEmpty() || input.matches(Regex("^\\d{0,4}(\\.\\d{0,2})?$"))) {
                            amountText = input
                            localError = null
                        }
                    },
                    label = "Amount in dollars (max $${MAX_ADHOC_PAYOUT_CENTS / 100})",
                    keyboardType = KeyboardType.Decimal,
                    singleLine = true,
                )

                GccTextField(
                    value = note,
                    onValueChange = { note = it.take(280) },
                    label = "Note (optional — shown to the worker)",
                    singleLine = false,
                )

                localError?.let { err ->
                    Text(err, color = GccError, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cents = amountCents ?: return@Button
                    sending = true
                    onSubmit(cents, note.trim().ifBlank { null }, idempotencyKey) { success, message ->
                        sending = false
                        if (!success) localError = message ?: "Failed to send payout"
                    }
                },
                enabled = canSubmit,
                colors = ButtonDefaults.buttonColors(containerColor = GccGreen),
            ) { Text(if (sending) "Sending…" else "Send") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !sending) { Text("Cancel") }
        },
    )
}

/** $1,000 cap, mirrored from the server's MAX_PAYOUT_CENTS. */
private const val MAX_ADHOC_PAYOUT_CENTS = 100_000

private fun parsePayoutDollarsToCents(input: String): Int? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null
    val parts = trimmed.split(".")
    val dollars = parts[0].toIntOrNull() ?: return null
    val cents = if (parts.size > 1) parts[1].padEnd(2, '0').take(2).toIntOrNull() ?: return null else 0
    return dollars * 100 + cents
}

@Composable
private fun GrantPointsDialog(
    workerName: String,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (amount: Int, reason: String, action: AdminGrantAction) -> Unit,
) {
    var amountText by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var selectedAction by remember { mutableStateOf(AdminGrantAction.ADMIN_GRANT) }
    var amountWasManuallyEdited by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }

    val parsedAmount = amountText.trim().toIntOrNull()
    val canSubmit = !isLoading &&
        parsedAmount != null && parsedAmount != 0 && kotlin.math.abs(parsedAmount) <= 10_000 &&
        reason.trim().isNotEmpty() && reason.trim().length <= 280

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Grant Points") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Award or correct points for $workerName. Use a negative amount to subtract.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                )

                // Amount
                GccTextField(
                    value = amountText,
                    onValueChange = { input ->
                        // Allow leading minus and digits only
                        if (input.isEmpty() || input == "-" || input.matches(Regex("^-?\\d*$"))) {
                            amountText = input
                            amountWasManuallyEdited = true
                            localError = null
                        }
                    },
                    label = "Amount (e.g. 25 or -10)",
                    keyboardType = KeyboardType.Number,
                    singleLine = true,
                )

                // Reason
                GccTextField(
                    value = reason,
                    onValueChange = { reason = it.take(280) },
                    label = "Reason (required, ≤ 280 chars)",
                    singleLine = false,
                )

                // Action category
                Text(
                    "Category",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextMuted,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AdminGrantAction.entries.forEach { action ->
                        FilterChip(
                            selected = selectedAction == action,
                            onClick = {
                                val previousDefault = selectedAction.defaultAmount?.toString()
                                selectedAction = action
                                if (
                                    !amountWasManuallyEdited ||
                                    amountText.isBlank() ||
                                    amountText == previousDefault
                                ) {
                                    amountText = action.defaultAmount?.toString().orEmpty()
                                    amountWasManuallyEdited = false
                                }
                            },
                            label = { Text(action.display) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = GccGreen.copy(alpha = 0.18f),
                                selectedLabelColor = GccGreenDark,
                            ),
                        )
                    }
                }

                localError?.let { err ->
                    Text(err, color = GccError, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val amt = parsedAmount ?: return@Button
                    val trimmed = reason.trim()
                    if (trimmed.isEmpty()) {
                        localError = "Reason is required"
                        return@Button
                    }
                    onSubmit(amt, trimmed, selectedAction)
                },
                enabled = canSubmit,
                colors = ButtonDefaults.buttonColors(containerColor = GccSky),
            ) { Text(if (isLoading) "Granting..." else "Grant") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) { Text("Cancel") }
        },
    )
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
            InfoRow(
                "Address",
                listOfNotNull(
                    worker.address.street.takeIf { it.isNotBlank() },
                    listOfNotNull(worker.address.city, worker.address.state)
                        .filter { it.isNotBlank() }
                        .joinToString(", ")
                        .takeIf { it.isNotBlank() },
                    worker.address.zip.takeIf { it.isNotBlank() },
                ).joinToString(", ").ifBlank { "—" },
            )
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
private fun PointsHistoryRow(entry: PointsLedgerEntry) {
    val isPositive = entry.points >= 0
    val pointsColor = if (isPositive) GccGreen else GccError
    val actionLabel = when (entry.action) {
        PointsAction.SPOT_CREATED -> "Spot reported"
        PointsAction.VERIFICATION_DIRTY -> "Verified — dirty"
        PointsAction.VERIFICATION_CLEAN -> "Verified — clean"
        PointsAction.VOLUNTEER_CLEANUP -> "Volunteer cleanup"
        PointsAction.ALREADY_CLEAN_REPORT -> "Already-clean report"
        PointsAction.ADMIN_GRANT -> "Admin grant"
        PointsAction.BUG_REPORT -> "Bug report"
        PointsAction.SCAN_COVERAGE_NEW_AREA -> "New area scanned"
        PointsAction.SCAN_COVERAGE_STALE -> "Stale area rescanned"
        PointsAction.SCAN_COVERAGE_RECTIFICATION -> "Coverage rectified"
        PointsAction.SCAN_COVERAGE_CLOSE_UPGRADE -> "Close-up scan"
        PointsAction.OBSERVATION_LOGGED -> "Scan logged"
        PointsAction.CIVIC_CAPTURE -> "311 evidence"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(GccSky.copy(alpha = 0.04f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    actionLabel,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    formatRelativeTime(entry.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSubtle,
                )
            }
            entry.reason?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    maxLines = 2,
                )
            }
        }
        Text(
            text = if (isPositive) "+${entry.points}" else entry.points.toString(),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = pointsColor,
        )
    }
}

private fun formatRelativeTime(then: kotlinx.datetime.Instant): String {
    val now = Clock.System.now()
    val deltaSec = (now - then).inWholeSeconds
    val abs = abs(deltaSec)
    return when {
        abs < 60 -> "just now"
        abs < 3600 -> "${abs / 60}m ago"
        abs < 86_400 -> "${abs / 3600}h ago"
        abs < 7 * 86_400 -> "${abs / 86_400}d ago"
        else -> {
            val ldt = then.toLocalDateTime(TimeZone.currentSystemDefault())
            "${ldt.monthNumber}/${ldt.dayOfMonth}"
        }
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
