package com.gcc.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gcc.app.platform.CameraManager
import com.gcc.app.platform.CapturedPhoto
import com.gcc.app.platform.pickFromLibrary
import com.gcc.app.ui.components.*
import com.gcc.app.ui.theme.*
import com.gcc.app.viewmodel.DocumentUploadViewModel
import org.koin.compose.koinInject

@Composable
fun DocumentUploadScreen(
    onComplete: () -> Unit = {},
    onSkip: () -> Unit = {},
) {
    val viewModel = koinInject<DocumentUploadViewModel>()
    val uiState by viewModel.uiState.collectAsState()
    val spacing = GccTheme.spacing

    DisposableEffect(Unit) {
        onDispose { viewModel.onCleared() }
    }

    LaunchedEffect(uiState.isComplete) {
        if (uiState.isComplete) onComplete()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // This Scaffold opts out of window insets and has no top bar, so the
                // safe area has to be re-applied here or the header sits under the
                // status bar / Dynamic Island.
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.lg, vertical = spacing.xl),
        ) {
            Text(
                "Verify Your Identity",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(spacing.sm))
            Text(
                "Upload documents to verify your identity and residency. This is required before you can claim blocks and start earning.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
            )

            Spacer(modifier = Modifier.height(spacing.xl))

            // --- Government ID ---
            DocumentUploadCard(
                title = "Government ID",
                description = "Driver's license, state ID, or passport",
                icon = "\uD83C\uDD94",
                photo = uiState.governmentIdPhoto,
                isUploaded = uiState.governmentIdUrl != null,
                onCapture = { viewModel.captureGovernmentId() },
                onPickLibrary = { viewModel.pickGovernmentIdFromLibrary() },
            )

            Spacer(modifier = Modifier.height(spacing.md))

            // --- Proof of Address ---
            DocumentUploadCard(
                title = "Proof of Address",
                description = "Utility bill, bank statement, or lease agreement",
                icon = "\uD83C\uDFE0",
                photo = uiState.proofOfAddressPhoto,
                isUploaded = uiState.proofOfAddressUrl != null,
                onCapture = { viewModel.captureProofOfAddress() },
                onPickLibrary = { viewModel.pickProofOfAddressFromLibrary() },
            )

            uiState.error?.let { error ->
                Spacer(modifier = Modifier.height(spacing.md))
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = GccError,
                )
            }

            Spacer(modifier = Modifier.height(spacing.xl))

            // Submit button
            GccButton(
                text = if (uiState.isUploading) "Uploading..." else "Submit Documents",
                onClick = { viewModel.submitDocuments() },
                variant = GccButtonVariant.PRIMARY,
                enabled = uiState.governmentIdPhoto != null && uiState.proofOfAddressPhoto != null && !uiState.isUploading,
                isLoading = uiState.isUploading,
            )

            Spacer(modifier = Modifier.height(spacing.sm))

            // Skip for now button
            TextButton(
                onClick = onSkip,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Skip for now",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(modifier = Modifier.height(spacing.xs))
            Text(
                "You can upload documents later from your profile, but you won't be able to claim blocks until verified.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun DocumentUploadCard(
    title: String,
    description: String,
    icon: String,
    photo: CapturedPhoto?,
    isUploaded: Boolean,
    onCapture: () -> Unit,
    onPickLibrary: () -> Unit,
) {
    val spacing = GccTheme.spacing

    GccCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(icon, style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.width(spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(spacing.xxs))
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                )
            }
            if (isUploaded || photo != null) {
                Spacer(modifier = Modifier.width(spacing.sm))
                Text(
                    "\u2705",
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }

        Spacer(modifier = Modifier.height(spacing.md))

        if (photo == null) {
            // Upload area
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                OutlinedButton(
                    onClick = onCapture,
                    modifier = Modifier.weight(1f).height(ControlHeight),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = GccGreen,
                    ),
                ) {
                    Text("\uD83D\uDCF7  Take Photo")
                }
                OutlinedButton(
                    onClick = onPickLibrary,
                    modifier = Modifier.weight(1f).height(ControlHeight),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = GccGreen,
                    ),
                ) {
                    Text("\uD83D\uDCC1  Choose File")
                }
            }
        } else {
            // Photo captured indicator
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ControlHeight)
                    .clip(RoundedCornerShape(12.dp))
                    .background(GccGreen.copy(alpha = 0.1f))
                    .border(1.dp, GccGreen.copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Photo captured (${photo.width}x${photo.height})",
                    style = MaterialTheme.typography.bodySmall,
                    color = GccGreen,
                )
            }

            Spacer(modifier = Modifier.height(spacing.sm))

            TextButton(
                onClick = onCapture,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Retake photo", color = TextMuted)
            }
        }
    }
}

/**
 * Shared height for the capture buttons and the captured-photo chip that replaces
 * them, so a card keeps its size when a document is added. Also clears the 44pt
 * minimum touch target, which OutlinedButton's 40dp default does not.
 */
private val ControlHeight = 48.dp
