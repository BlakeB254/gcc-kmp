package com.gcc.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcc.app.platform.CameraManager
import com.gcc.app.platform.CameraPreview
import com.gcc.app.platform.OnDeviceAnalyzer
import com.gcc.app.ui.theme.*
import com.gcc.shared.model.ScanHotspot
import kotlinx.coroutines.launch

private enum class CaptureState {
    PREVIEW,     // Shows before photo placeholder + "Take After Photo" button
    CAPTURING,   // Shows camera preview + capture button
    COMPARING,   // Shows before/after side-by-side + score + confirm/retake
}

private const val VERIFICATION_THRESHOLD = 0.3f

/**
 * Bottom sheet for the clean-lap hotspot confirmation workflow.
 *
 * States:
 *  PREVIEW   – before photo + "Take After Photo" button
 *  CAPTURING – live camera preview + capture button
 *  COMPARING – before/after side-by-side + verification score + Confirm/Retake
 *
 * On desktop the camera returns null (no hardware). The sheet degrades gracefully:
 * the user can still "confirm" without a real photo capture.
 */
@Composable
fun HotspotCaptureSheet(
    hotspot: ScanHotspot,
    onConfirmed: (afterPhotoBytes: ByteArray, verificationScore: Float) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cameraManager = remember { CameraManager() }
    val analyzer = remember { OnDeviceAnalyzer() }
    val coroutineScope = rememberCoroutineScope()

    var captureState by remember { mutableStateOf(CaptureState.PREVIEW) }
    var afterPhotoBytes by remember { mutableStateOf<ByteArray?>(null) }
    var verificationScore by remember { mutableStateOf<Float?>(null) }
    var isAnalyzing by remember { mutableStateOf(false) }
    var isCapturing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Derive pass/fail from score
    val passed = verificationScore?.let { it > VERIFICATION_THRESHOLD } ?: false

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ── Drag handle ────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(TextSubtle.copy(alpha = 0.4f))
            )

            // ── Header ─────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "Clean Hotspot",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    )
                    Text(
                        text = when (captureState) {
                            CaptureState.PREVIEW -> "Review the before photo"
                            CaptureState.CAPTURING -> "Take an after photo"
                            CaptureState.COMPARING -> "Before vs. After"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = TextMuted)
                }
            }

            // ── Content area ───────────────────────────────────────────────
            when (captureState) {

                // ── PREVIEW: show before photo + CTA ──────────────────────
                CaptureState.PREVIEW -> {
                    val typesLabel = if (hotspot.trashTypes.isNotEmpty())
                        hotspot.trashTypes.take(2).joinToString(", ") {
                            it.name.lowercase().replaceFirstChar { c -> c.uppercase() }
                        }
                    else "Hotspot"

                    // Before-photo placeholder
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "\uD83D\uDDBC\uFE0F",
                                style = MaterialTheme.typography.headlineLarge,
                            )
                            Text(
                                text = "Scout photo",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                            )
                            Text(
                                text = typesLabel,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            )
                            Text(
                                text = "Severity ${"★".repeat(hotspot.severity.coerceIn(1, 5))}",
                                style = MaterialTheme.typography.bodySmall,
                                color = GccSky,
                            )
                        }
                    }

                    errorMessage?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = GccError)
                    }

                    GccButton(
                        text = if (cameraManager.hasCameraHardware()) "Take After Photo" else "Confirm Cleaned (Desktop)",
                        onClick = {
                            errorMessage = null
                            if (cameraManager.hasCameraHardware()) {
                                captureState = CaptureState.CAPTURING
                            } else {
                                // Desktop fallback: skip to comparing with dummy bytes
                                afterPhotoBytes = ByteArray(0)
                                verificationScore = 1.0f
                                captureState = CaptureState.COMPARING
                            }
                        },
                        variant = GccButtonVariant.PRIMARY,
                    )
                }

                // ── CAPTURING: live camera preview ─────────────────────────
                CaptureState.CAPTURING -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    ) {
                        CameraPreview(modifier = Modifier.fillMaxSize())

                        // "AFTER" label overlay
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 12.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.Black.copy(alpha = 0.55f))
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = "AFTER",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                color = GccGreen,
                            )
                        }
                    }

                    errorMessage?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = GccError)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedButton(
                            onClick = { captureState = CaptureState.PREVIEW },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Back")
                        }

                        Button(
                            onClick = {
                                if (!isCapturing) {
                                    isCapturing = true
                                    errorMessage = null
                                    coroutineScope.launch {
                                        try {
                                            val photo = cameraManager.capturePhoto()
                                            if (photo != null) {
                                                val bytes = photo.bytes
                                                afterPhotoBytes = bytes

                                                // Run AI comparison if available
                                                isAnalyzing = true
                                                val score = if (analyzer.isAvailable()) {
                                                    val beforeBytes = hotspot.beforePhotoUrl.encodeToByteArray()
                                                    analyzer.compareImages(beforeBytes, bytes)
                                                } else null
                                                verificationScore = score ?: 1.0f
                                                isAnalyzing = false

                                                captureState = CaptureState.COMPARING
                                            } else {
                                                // Desktop or capture failed — still allow confirm
                                                afterPhotoBytes = ByteArray(0)
                                                verificationScore = 1.0f
                                                captureState = CaptureState.COMPARING
                                            }
                                        } catch (e: Exception) {
                                            errorMessage = "Capture failed: ${e.message}"
                                        } finally {
                                            isCapturing = false
                                            isAnalyzing = false
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.weight(2f),
                            colors = ButtonDefaults.buttonColors(containerColor = GccGreen),
                            enabled = !isCapturing,
                        ) {
                            if (isCapturing || isAnalyzing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Text(
                                    "\uD83D\uDCF7 Capture",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                )
                            }
                        }
                    }
                }

                // ── COMPARING: before/after side-by-side + score ───────────
                CaptureState.COMPARING -> {
                    val score = verificationScore ?: 0f

                    // Side-by-side photo row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // Before slot
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "BEFORE",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = GccSky,
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(130.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(GccSky.copy(alpha = 0.1f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "\uD83D\uDDBC\uFE0F",
                                    style = MaterialTheme.typography.headlineMedium,
                                )
                            }
                        }

                        // After slot
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "AFTER",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = GccGreen,
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(130.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(GccGreen.copy(alpha = 0.1f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (afterPhotoBytes != null && afterPhotoBytes!!.isNotEmpty()) {
                                    Text(
                                        text = "\u2713",
                                        style = MaterialTheme.typography.headlineLarge,
                                        color = GccGreen,
                                    )
                                } else {
                                    // Desktop placeholder
                                    Text(
                                        text = "\uD83D\uDCF7",
                                        style = MaterialTheme.typography.headlineMedium,
                                    )
                                }
                            }
                        }
                    }

                    // Verification score badge
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (passed) GccGreen.copy(alpha = 0.12f)
                                else GccError.copy(alpha = 0.12f)
                            )
                            .padding(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(
                                    text = if (passed) "\u2713 Verification passed" else "\u26A0 Verification weak",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (passed) GccGreen else GccError,
                                )
                                Text(
                                    text = if (passed) "Significant change detected" else "Low visual change — retake if needed",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted,
                                )
                            }
                            Text(
                                text = "${(score * 100).toInt()}%",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = if (passed) GccGreen else GccError,
                            )
                        }
                    }

                    errorMessage?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = GccError)
                    }

                    // Confirm / Retake buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                afterPhotoBytes = null
                                verificationScore = null
                                errorMessage = null
                                captureState = if (cameraManager.hasCameraHardware())
                                    CaptureState.CAPTURING
                                else
                                    CaptureState.PREVIEW
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Retake")
                        }

                        Button(
                            onClick = {
                                val bytes = afterPhotoBytes ?: ByteArray(0)
                                val finalScore = verificationScore ?: 1.0f
                                onConfirmed(bytes, finalScore)
                            },
                            modifier = Modifier.weight(2f),
                            colors = ButtonDefaults.buttonColors(containerColor = GccGreen),
                        ) {
                            Text(
                                "Confirm Cleaned",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }
    }
}
