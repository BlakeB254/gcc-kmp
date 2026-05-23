package com.gcc.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import com.gcc.app.platform.CapturedPhoto
import com.gcc.app.platform.OnDeviceAnalyzer
import com.gcc.shared.model.OnDeviceAnalysisResult
import com.gcc.app.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Full-screen dialog that opens the camera for manual hotspot capture during a scout lap.
 *
 * Opened by the "📷" FAB on ScoutScreen when the worker manually spots trash.
 *
 * Flow:
 *  1. Shows live camera preview
 *  2. Worker taps capture
 *  3. OnDeviceAnalyzer.analyzePhoto() runs in the background
 *  4. Returns both the raw bytes and the analysis result to the caller
 *
 * On desktop: camera returns null — the dialog allows confirming without a real photo.
 */
@Composable
fun ScoutCaptureDialog(
    onCaptured: (photo: CapturedPhoto, analysisResult: OnDeviceAnalysisResult?, cleanupTarget: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val cameraManager = remember { CameraManager() }
    val analyzer = remember { OnDeviceAnalyzer() }
    val coroutineScope = rememberCoroutineScope()

    var isCapturing by remember { mutableStateOf(false) }
    var isAnalyzing by remember { mutableStateOf(false) }
    var capturedPhoto by remember { mutableStateOf<CapturedPhoto?>(null) }
    var analysisResult by remember { mutableStateOf<OnDeviceAnalysisResult?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var cleanupTarget by remember { mutableStateOf(true) }

    val hasCaptured = capturedPhoto != null

    // Full-screen dialog
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (!hasCaptured) {
            // ── Live camera preview ──────────────────────────────────────
            CameraPreview(modifier = Modifier.fillMaxSize())

            // Label
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = "SCOUT CAPTURE",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = GccError,
                )
            }

            errorMessage?.let { err ->
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 72.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(GccError.copy(alpha = 0.85f))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text(err, style = MaterialTheme.typography.bodySmall, color = Color.White)
                }
            }

            // Bottom controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 32.dp, start = 32.dp, end = 32.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Cancel button
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = Color.White, style = MaterialTheme.typography.bodyLarge)
                }

                // Capture button
                Button(
                    onClick = {
                        if (!isCapturing) {
                            isCapturing = true
                            errorMessage = null
                            coroutineScope.launch {
                                try {
                                    val photo = cameraManager.capturePhoto()
                                    if (photo != null) {
                                        capturedPhoto = photo
                                        // Run on-device analysis
                                        isAnalyzing = true
                                        if (analyzer.isAvailable()) {
                                            analysisResult = analyzer.analyzePhoto(
                                                imageBytes = photo.bytes,
                                                depthAnalysis = photo.depthAnalysis,
                                            )
                                            if (analysisResult?.suggestedTags?.isEmpty() == true &&
                                                analysisResult?.trashTypes?.isEmpty() == true
                                            ) {
                                                cleanupTarget = false
                                            }
                                        }
                                        isAnalyzing = false
                                    } else {
                                        // Desktop / no camera: emit empty bytes immediately
                                        capturedPhoto = CapturedPhoto(bytes = ByteArray(0), width = 0, height = 0)
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
                    modifier = Modifier.size(72.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                    contentPadding = PaddingValues(0.dp),
                    enabled = !isCapturing,
                ) {
                    if (isCapturing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            color = GccError,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(GccError),
                        )
                    }
                }

                // Spacer for button symmetry
                Spacer(modifier = Modifier.width(48.dp))
            }

        } else {
            // ── Post-capture review screen ───────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Spacer(modifier = Modifier.statusBarsPadding())

                Text(
                    text = "Hotspot Captured",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                )

                // Photo preview placeholder
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (capturedPhoto?.bytes?.isEmpty() != false) {
                        // Desktop placeholder
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("\uD83D\uDCF7", style = MaterialTheme.typography.headlineLarge)
                            Text("Desktop — no camera", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                        }
                    } else {
                        Text(
                            text = "\u2713 Photo captured",
                            style = MaterialTheme.typography.headlineMedium,
                            color = GccGreen,
                        )
                    }
                }

                // AI analysis result
                if (isAnalyzing) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = GccGreen,
                            strokeWidth = 2.dp,
                        )
                        Text(
                            "Analyzing photo…",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                        )
                    }
                } else {
                    analysisResult?.let { result ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(GccGreen.copy(alpha = 0.1f))
                                .padding(12.dp),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "AI Analysis",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = GccGreen,
                                )
                                if (result.trashTypes.isNotEmpty()) {
                                    Text(
                                        text = "Detected: ${result.trashTypes.take(3).joinToString(", ")}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextMuted,
                                    )
                                }
                                result.severity?.let { sev ->
                                    Text(
                                        text = "Severity: ${"★".repeat(sev.coerceIn(1, 5))}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = GccSky,
                                    )
                                }
                                result.confidence?.let { conf ->
                                    Text(
                                        text = "Confidence: ${(conf * 100).toInt()}%",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextMuted,
                                    )
                                }
                            }
                        }
                    } ?: run {
                        // No AI available
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(TextSubtle.copy(alpha = 0.1f))
                                .padding(12.dp),
                        ) {
                            Text(
                                text = "On-device AI not available — hotspot will be logged without analysis",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(TextSubtle.copy(alpha = 0.08f))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Checkbox(
                        checked = !cleanupTarget,
                        onCheckedChange = { checked -> cleanupTarget = !checked },
                    )
                    Column {
                        Text(
                            text = "Document only",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        )
                        Text(
                            text = "Property lot or street lane",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            capturedPhoto = null
                            analysisResult = null
                            errorMessage = null
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Retake")
                    }

                    Button(
                        onClick = {
                            val photo = capturedPhoto ?: CapturedPhoto(bytes = ByteArray(0), width = 0, height = 0)
                            onCaptured(photo, analysisResult, cleanupTarget)
                        },
                        modifier = Modifier.weight(2f),
                        colors = ButtonDefaults.buttonColors(containerColor = GccError),
                        enabled = !isAnalyzing,
                    ) {
                        Text(
                            if (cleanupTarget) "Add Cleanup Target" else "Document Observation",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        )
                    }
                }

                Spacer(modifier = Modifier.navigationBarsPadding())
            }
        }
    }
}
