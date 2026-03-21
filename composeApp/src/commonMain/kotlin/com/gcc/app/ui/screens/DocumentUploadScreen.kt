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
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "Verify Your Identity",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Upload documents to verify your identity and residency. This is required before you can claim blocks and start earning.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
            )

            Spacer(modifier = Modifier.height(24.dp))

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

            Spacer(modifier = Modifier.height(16.dp))

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
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = GccError,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Submit button
            GccButton(
                text = if (uiState.isUploading) "Uploading..." else "Submit Documents",
                onClick = { viewModel.submitDocuments() },
                variant = GccButtonVariant.PRIMARY,
                enabled = uiState.governmentIdPhoto != null && uiState.proofOfAddressPhoto != null && !uiState.isUploading,
                isLoading = uiState.isUploading,
            )

            Spacer(modifier = Modifier.height(12.dp))

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

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "You can upload documents later from your profile, but you won't be able to claim blocks until verified.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(32.dp))
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
    GccCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 4.dp),
        ) {
            Text(icon, style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                )
            }
            if (isUploaded || photo != null) {
                Text(
                    "\u2705",
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (photo == null) {
            // Upload area
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onCapture,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = GccGreen,
                    ),
                ) {
                    Text("\uD83D\uDCF7  Take Photo")
                }
                OutlinedButton(
                    onClick = onPickLibrary,
                    modifier = Modifier.weight(1f),
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
                    .height(48.dp)
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

            Spacer(modifier = Modifier.height(8.dp))

            TextButton(
                onClick = onCapture,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Retake photo", color = TextMuted)
            }
        }
    }
}
