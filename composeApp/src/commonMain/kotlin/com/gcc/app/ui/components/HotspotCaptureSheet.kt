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
import com.gcc.shared.model.OnDeviceAnalysisResult
import com.gcc.shared.model.ScanHotspot
import kotlinx.coroutines.launch

internal enum class CaptureState {
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
    onConfirmed: (afterPhotoBytes: ByteArray, verificationScore: Float?) -> Unit,
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

    val badge = afterPhotoBadge(afterPhotoBytes, verificationScore)

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
                                // No camera (desktop): the compare shows "Photo not
                                // captured" and Confirm stays disabled.
                                afterPhotoBytes = null
                                verificationScore = null
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

                                                isAnalyzing = true
                                                val analysis = if (analyzer.isAvailable()) {
                                                    analyzer.analyzePhoto(
                                                        imageBytes = bytes,
                                                        depthAnalysis = photo.depthAnalysis,
                                                    )
                                                } else null
                                                val outcome = afterPhotoOutcome(analysis)
                                                verificationScore = outcome.suggestedScore
                                                isAnalyzing = false
                                                captureState = outcome.state
                                            } else {
                                                // Capture failed (or no camera): no photo, no
                                                // score. The compare says "Photo not captured" and
                                                // Confirm stays disabled until a Retake succeeds.
                                                afterPhotoBytes = null
                                                verificationScore = null
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
                            .clip(RoundedCornerShape(12.dp))
                            .background(badge.background)
                            .padding(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = badge.title,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = badge.textColor,
                                )
                                Text(
                                    text = badge.detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted,
                                )
                            }
                            badgePercent(verificationScore, badge)?.let { percent ->
                                Text(
                                    text = percent,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = badge.textColor,
                                )
                            }
                        }
                    }

                    errorMessage?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = GccErrorDark)
                    }

                    val payload = confirmPayload(afterPhotoBytes, verificationScore)
                    if (payload == null) {
                        // Inside the sheet, above the buttons — not under the scrim.
                        Text(
                            "Confirm needs an after photo. Tap Retake to take it again.",
                            style = MaterialTheme.typography.bodySmall,
                            color = GccErrorDark,
                        )
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
                            onClick = { payload?.let { onConfirmed(it.afterPhotoBytes, it.verificationScore) } },
                            enabled = payload != null,
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

/** How the compare describes the after photo. */
internal enum class AfterPhotoBadge(
    val title: String,
    val detail: String,
    val textColor: Color,
    val background: Color,
) {
    PHOTO_NOT_CAPTURED(
        "Photo not captured", "No after photo to confirm with.", GccErrorDark, GccError.copy(alpha = 0.10f),
    ),
    NOT_ANALYSED(
        "Not analysed", "No on-device check — compare the photos yourself.", GccBlueDark, GccBlueLight.copy(alpha = 0.6f),
    ),
    LOOKS_CLEAN(
        "Looks clean", "Suggestion only — you confirm.", GccGreenDark, GccGreen.copy(alpha = 0.12f),
    ),
    LITTER_MAY_REMAIN(
        "Litter may remain", "Check the after photo — retake if needed.", GccWarningDark, GccClaimed.copy(alpha = 0.14f),
    ),
}

internal fun afterPhotoBadge(afterPhotoBytes: ByteArray?, suggestedScore: Float?): AfterPhotoBadge = when {
    afterPhotoBytes == null || afterPhotoBytes.isEmpty() -> AfterPhotoBadge.PHOTO_NOT_CAPTURED
    suggestedScore == null -> AfterPhotoBadge.NOT_ANALYSED
    suggestedScore > VERIFICATION_THRESHOLD -> AfterPhotoBadge.LOOKS_CLEAN
    else -> AfterPhotoBadge.LITTER_MAY_REMAIN
}

/** The suggestion as a percentage, only when there is a real analysed score. */
internal fun badgePercent(suggestedScore: Float?, badge: AfterPhotoBadge): String? =
    if (badge == AfterPhotoBadge.LOOKS_CLEAN || badge == AfterPhotoBadge.LITTER_MAY_REMAIN) {
        suggestedScore?.let { "${(it * 100).toInt()}%" }
    } else null

/** What the worker's Confirm tap sends. */
internal class ConfirmPayload(val afterPhotoBytes: ByteArray, val verificationScore: Float?)

/**
 * Null = Confirm is disabled (no after photo). The score passes through
 * unchanged — a missing analysis is sent as null, never defaulted to 1.0.
 */
internal fun confirmPayload(afterPhotoBytes: ByteArray?, suggestedScore: Float?): ConfirmPayload? =
    afterPhotoBytes?.takeIf { it.isNotEmpty() }?.let { ConfirmPayload(it, suggestedScore) }

/** What the sheet does once the after photo has been analysed. */
internal data class AfterPhotoOutcome(val state: CaptureState, val suggestedScore: Float?)

/**
 * ALWAYS the before/after compare: a cleaned section is confirmed only by the
 * worker's tap, whatever the on-device analysis says (it can be missing,
 * label-less or simply wrong). The score only pre-fills the compare's
 * suggestion; trust comes from server-side checks and AI review, not from the
 * phone. Null score = no analysis.
 */
internal fun afterPhotoOutcome(analysis: OnDeviceAnalysisResult?): AfterPhotoOutcome =
    AfterPhotoOutcome(CaptureState.COMPARING, analysis?.let { cleanVerificationScore(it) })

private fun hasTrashSignal(analysis: OnDeviceAnalysisResult): Boolean =
    analysis.suggestedTags.isNotEmpty() || analysis.trashTypes.isNotEmpty()

/** Suggestion shown in the compare: 1.0 = looks clean, down to 0.2 = litter still there. */
internal fun cleanVerificationScore(analysis: OnDeviceAnalysisResult): Float {
    val severity = analysis.severity ?: 1
    return when {
        !hasTrashSignal(analysis) -> 1.0f
        severity <= 1 -> 0.85f
        severity == 2 && (analysis.confidence ?: 0f) < 0.35f -> 0.70f
        else -> 0.20f
    }
}
