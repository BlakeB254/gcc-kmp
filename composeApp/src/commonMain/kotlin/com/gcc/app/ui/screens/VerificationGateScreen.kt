package com.gcc.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.components.*
import com.gcc.app.ui.theme.*
import com.gcc.shared.model.DocumentStatus
import com.gcc.shared.model.VerificationStatus
import com.gcc.shared.model.WorkerVerification

/**
 * Verification gate — intercepts actions that require a verified account.
 *
 * Shows one of three states:
 * 1. NO DOCUMENTS: Prompt to upload government ID + proof of address
 * 2. PENDING REVIEW: Documents submitted, waiting for admin approval
 * 3. REJECTED: Documents rejected, prompt to resubmit
 */
@Composable
fun VerificationGateScreen(
    verification: WorkerVerification,
    actionLabel: String = "claim blocks",
    onUploadDocuments: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    val overallStatus = verification.status
    val govIdStatus = verification.governmentIdStatus
    val addressStatus = verification.proofOfAddressStatus

    Scaffold(
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            when {
                // Case 1: No documents uploaded yet
                govIdStatus == DocumentStatus.PENDING && addressStatus == DocumentStatus.PENDING
                        && overallStatus == VerificationStatus.PENDING -> {
                    NoDocumentsView(
                        actionLabel = actionLabel,
                        onUploadDocuments = onUploadDocuments,
                        onBack = onBack,
                    )
                }

                // Case 2: Documents rejected — need resubmission
                overallStatus == VerificationStatus.REJECTED
                        || govIdStatus == DocumentStatus.REJECTED
                        || govIdStatus == DocumentStatus.RESUBMIT
                        || addressStatus == DocumentStatus.REJECTED
                        || addressStatus == DocumentStatus.RESUBMIT -> {
                    RejectedView(
                        verification = verification,
                        onResubmit = onUploadDocuments,
                        onBack = onBack,
                    )
                }

                // Case 3: Needs more info
                overallStatus == VerificationStatus.NEEDS_INFO -> {
                    NeedsInfoView(
                        reviewNotes = verification.reviewNotes,
                        onUploadDocuments = onUploadDocuments,
                        onBack = onBack,
                    )
                }

                // Case 4: Documents under review (pending but submitted)
                else -> {
                    PendingReviewView(
                        actionLabel = actionLabel,
                        onBack = onBack,
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun NoDocumentsView(
    actionLabel: String,
    onUploadDocuments: () -> Unit,
    onBack: () -> Unit,
) {
    Text("\uD83D\uDD12", style = MaterialTheme.typography.displayMedium)
    Spacer(modifier = Modifier.height(16.dp))

    Text(
        "Verification Required",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Spacer(modifier = Modifier.height(8.dp))

    Text(
        "You need to verify your identity before you can $actionLabel. This helps keep our community safe.",
        style = MaterialTheme.typography.bodyMedium,
        color = TextMuted,
        textAlign = TextAlign.Center,
    )

    Spacer(modifier = Modifier.height(24.dp))

    GccCard(accentColor = GccGreen) {
        Text(
            "What you'll need:",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        DocumentRequirement("\uD83C\uDD94", "Government-issued photo ID", "Driver's license, state ID, or passport")
        Spacer(modifier = Modifier.height(6.dp))
        DocumentRequirement("\uD83C\uDFE0", "Proof of address", "Utility bill, bank statement, or lease")
    }

    Spacer(modifier = Modifier.height(24.dp))

    GccButton(
        text = "Upload Documents",
        onClick = onUploadDocuments,
        variant = GccButtonVariant.PRIMARY,
    )

    Spacer(modifier = Modifier.height(12.dp))

    TextButton(onClick = onBack) {
        Text("Go Back", color = TextMuted)
    }
}

@Composable
private fun PendingReviewView(
    actionLabel: String,
    onBack: () -> Unit,
) {
    Text("\u23F3", style = MaterialTheme.typography.displayMedium)
    Spacer(modifier = Modifier.height(16.dp))

    Text(
        "Under Review",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Spacer(modifier = Modifier.height(8.dp))

    Text(
        "Your documents have been submitted and are being reviewed. You'll be able to $actionLabel once approved.",
        style = MaterialTheme.typography.bodyMedium,
        color = TextMuted,
        textAlign = TextAlign.Center,
    )

    Spacer(modifier = Modifier.height(16.dp))

    GccCard(accentColor = GccSky) {
        Text(
            "Review typically takes 1–2 business days. You'll receive a notification when your account is approved.",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
        )
    }

    Spacer(modifier = Modifier.height(24.dp))

    GccButton(
        text = "Go Back",
        onClick = onBack,
        variant = GccButtonVariant.OUTLINED,
    )
}

@Composable
private fun RejectedView(
    verification: WorkerVerification,
    onResubmit: () -> Unit,
    onBack: () -> Unit,
) {
    Text("\u26A0\uFE0F", style = MaterialTheme.typography.displayMedium)
    Spacer(modifier = Modifier.height(16.dp))

    Text(
        "Documents Need Attention",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Spacer(modifier = Modifier.height(8.dp))

    Text(
        "Some of your verification documents were not accepted. Please review the feedback below and resubmit.",
        style = MaterialTheme.typography.bodyMedium,
        color = TextMuted,
        textAlign = TextAlign.Center,
    )

    Spacer(modifier = Modifier.height(16.dp))

    // Show which documents need resubmission
    GccCard(accentColor = GccSky) {
        if (verification.governmentIdStatus == DocumentStatus.REJECTED
            || verification.governmentIdStatus == DocumentStatus.RESUBMIT) {
            DocumentStatusRow("\uD83C\uDD94", "Government ID", "Needs resubmission")
            Spacer(modifier = Modifier.height(6.dp))
        }
        if (verification.proofOfAddressStatus == DocumentStatus.REJECTED
            || verification.proofOfAddressStatus == DocumentStatus.RESUBMIT) {
            DocumentStatusRow("\uD83C\uDFE0", "Proof of Address", "Needs resubmission")
            Spacer(modifier = Modifier.height(6.dp))
        }
        verification.reviewNotes?.let { notes ->
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = TextMuted.copy(alpha = 0.2f))
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Admin feedback: $notes",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
        }
    }

    Spacer(modifier = Modifier.height(24.dp))

    GccButton(
        text = "Resubmit Documents",
        onClick = onResubmit,
        variant = GccButtonVariant.PRIMARY,
    )

    Spacer(modifier = Modifier.height(12.dp))

    TextButton(onClick = onBack) {
        Text("Go Back", color = TextMuted)
    }
}

@Composable
private fun NeedsInfoView(
    reviewNotes: String?,
    onUploadDocuments: () -> Unit,
    onBack: () -> Unit,
) {
    Text("\u2139\uFE0F", style = MaterialTheme.typography.displayMedium)
    Spacer(modifier = Modifier.height(16.dp))

    Text(
        "More Information Needed",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Spacer(modifier = Modifier.height(8.dp))

    Text(
        "We need additional information to complete your verification.",
        style = MaterialTheme.typography.bodyMedium,
        color = TextMuted,
        textAlign = TextAlign.Center,
    )

    reviewNotes?.let { notes ->
        Spacer(modifier = Modifier.height(16.dp))
        GccCard(accentColor = GccBlue) {
            Text(notes, style = MaterialTheme.typography.bodyMedium)
        }
    }

    Spacer(modifier = Modifier.height(24.dp))

    GccButton(
        text = "Upload Documents",
        onClick = onUploadDocuments,
        variant = GccButtonVariant.PRIMARY,
    )

    Spacer(modifier = Modifier.height(12.dp))

    TextButton(onClick = onBack) {
        Text("Go Back", color = TextMuted)
    }
}

@Composable
private fun DocumentRequirement(icon: String, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(icon, style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextMuted)
        }
    }
}

@Composable
private fun DocumentStatusRow(icon: String, title: String, status: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(icon, style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(status, style = MaterialTheme.typography.bodySmall, color = GccError)
    }
}
