package com.gcc.app.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.components.GccButton
import com.gcc.app.ui.components.GccButtonVariant
import com.gcc.app.ui.theme.GccGreen

/**
 * Bottom sheet for reviewing before/after photo pairs.
 *
 * Shows side-by-side comparison and allows the worker to retake
 * or confirm the photos before uploading.
 */
@Composable
fun PhotoComparisonSheet(
    checkpointName: String,
    hasBeforePhoto: Boolean,
    hasAfterPhoto: Boolean,
    onTakeBeforePhoto: () -> Unit,
    onTakeAfterPhoto: () -> Unit,
    onConfirmAndUpload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(20.dp),
    ) {
        // Header
        Text(
            text = checkpointName,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Photo verification required",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Photo slots side by side
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PhotoSlot(
                label = "Before",
                hasPhoto = hasBeforePhoto,
                onTakePhoto = onTakeBeforePhoto,
                modifier = Modifier.weight(1f),
            )
            PhotoSlot(
                label = "After",
                hasPhoto = hasAfterPhoto,
                onTakePhoto = onTakeAfterPhoto,
                enabled = hasBeforePhoto,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Upload button
        GccButton(
            text = "Confirm & Upload",
            onClick = onConfirmAndUpload,
            variant = GccButtonVariant.PRIMARY,
            enabled = hasBeforePhoto && hasAfterPhoto,
        )
    }
}

@Composable
private fun PhotoSlot(
    label: String,
    hasPhoto: Boolean,
    onTakePhoto: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (hasPhoto) GccGreen.copy(alpha = 0.1f)
                    else Color.Gray.copy(alpha = 0.1f)
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (hasPhoto) {
                // In production: AsyncImage(photoUri) from Coil
                Text(
                    text = "\u2713",
                    style = MaterialTheme.typography.headlineMedium,
                    color = GccGreen,
                )
            } else {
                TextButton(
                    onClick = onTakePhoto,
                    enabled = enabled,
                ) {
                    Text(
                        text = "\uD83D\uDCF7 Take Photo",
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
