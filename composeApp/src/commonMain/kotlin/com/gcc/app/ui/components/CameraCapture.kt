package com.gcc.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.gcc.app.platform.CameraManager
import com.gcc.app.platform.CameraPreview
import com.gcc.app.platform.CapturedPhoto
import com.gcc.app.platform.decodeImageBitmap
import com.gcc.app.platform.pickFromLibrary
import com.gcc.app.platform.hasLiDAR
import com.gcc.app.ui.map.GccMap
import com.gcc.app.ui.map.MapStyles
import com.gcc.app.ui.theme.*
import com.gcc.shared.model.Coordinates
import kotlinx.coroutines.launch

/**
 * Reusable full-screen camera composable for capturing photos.
 *
 * The viewfinder is live and capture-ready as soon as it opens — there is no
 * shutter button. Tapping anywhere on the preview captures a photo of what
 * the user is pointing at (Measure-app style: the scan is always active).
 *
 * Shows a live camera preview with:
 * - Photo count badge (bottom-left)
 * - Tap-to-capture hint chip (bottom-center)
 * - Optional mini-map overlay with heading cone (bottom-end)
 * - Thumbnail strip of captured photos
 *
 * Used by both SpotScreen camera phase and admin spot creation.
 *
 * @param cameraManager pass a shared instance when the caller also drives
 *   captures itself (e.g. SpotScanCapture's automatic first scan); when null
 *   a private instance is created.
 */
@Composable
fun CameraCapture(
    capturedPhotos: List<CapturedPhoto>,
    onCapture: (CapturedPhoto) -> Unit,
    onRemovePhoto: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
    maxPhotos: Int = 3,
    showMiniMap: Boolean = false,
    currentLocation: Coordinates? = null,
    gpsHeading: Float? = null,
    allowLibraryPick: Boolean = false,
    cameraManager: CameraManager? = null,
    /** Zones the environment scan has credited — minimap green highlight. */
    scanCellCenters: List<Pair<Double, Double>> = emptyList(),
    /** Geo-tagged trash items — minimap orange dots. */
    trashItems: List<Coordinates> = emptyList(),
    /** Ledger freshness for the minimap: never-scanned (fog) / stale (amber). */
    fogCellCenters: List<Pair<Double, Double>> = emptyList(),
    staleCellCenters: List<Pair<Double, Double>> = emptyList(),
    /** Environment-scan presentation: hides the photo-count/thumbnail chrome
     *  (captures are backend raw material, not a user-facing slideshow). */
    environmentScanMode: Boolean = false,
    statusText: String? = null,
) {
    val manager = cameraManager ?: remember { CameraManager() }
    val coroutineScope = rememberCoroutineScope()
    var isCapturing by remember { mutableStateOf(false) }
    val photoCount = capturedPhotos.size
    val atLimit = photoCount >= maxPhotos
    val hasDepthCapture = capturedPhotos.any { it.depthAnalysis?.hasDepth == true }
    val canCaptureDepth = !allowLibraryPick && capturedPhotos.isEmpty() && hasLiDAR()

    // Full-bleed viewfinder: the preview IS the screen; every control floats
    // above it (no rounded clip, no reserved strips — see build-107 field
    // report: the old Column letterboxed the camera).
    Box(
        modifier = modifier.fillMaxSize(),
    ) {
        // Live camera preview with overlays — tap anywhere to capture
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(atLimit) {
                    detectTapGestures {
                        if (!isCapturing && !atLimit) {
                            isCapturing = true
                            coroutineScope.launch {
                                // Environment scan: silent frame grab (no
                                // shutter); other flows keep the photo output
                                // (depth + attestation).
                                val photo = if (environmentScanMode) {
                                    manager.captureFrame()
                                } else {
                                    manager.capturePhoto()
                                }
                                if (photo != null) onCapture(photo)
                                isCapturing = false
                            }
                        }
                    }
                },
        ) {
            CameraPreview(modifier = Modifier.fillMaxSize())

            // Capture-in-progress indicator
            if (isCapturing) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center).size(40.dp),
                    color = GccGreen,
                    strokeWidth = 3.dp,
                )
            }

            // Tap-to-capture hint (bottom-center, above the badges)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 140.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = statusText ?: when {
                        atLimit -> "Max $maxPhotos photos captured"
                        isCapturing -> "Capturing..."
                        else -> "Tap an item to capture it"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
            }

            // Mini-map overlay (bottom-end)
            if (showMiniMap && currentLocation != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(end = 10.dp, bottom = 176.dp)
                        .size(width = 140.dp, height = 110.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.3f)),
                ) {
                    GccMap(
                        modifier = Modifier.fillMaxSize(),
                        centerLat = currentLocation.lat,
                        centerLng = currentLocation.lng,
                        zoom = MapStyles.ZOOM_ACTIVE_JOB,
                        currentLocation = currentLocation,
                        gpsHeading = gpsHeading,
                    )
                    // Scan coverage + trash + ledger freshness, projected over
                    // the fixed-zoom minimap.
                    ScanMinimapOverlay(
                        centerLat = currentLocation.lat,
                        centerLng = currentLocation.lng,
                        zoom = MapStyles.ZOOM_ACTIVE_JOB,
                        scanCellCenters = scanCellCenters,
                        cellSizeMeters = 3.0,
                        trashItems = trashItems,
                        fogCellCenters = fogCellCenters,
                        staleCellCenters = staleCellCenters,
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "\uD83D\uDCCD",
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                "You are here",
                                style = MaterialTheme.typography.labelSmall,
                                color = GccGreen,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }

            // Photo count badge (bottom-start, raised above Add details) —
            // hidden in environment-scan mode (captures are backend inputs).
            if (!environmentScanMode) Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(start = 10.dp, bottom = 88.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    if (atLimit) "$photoCount/$maxPhotos (max)"
                    else if (photoCount > 0) "$photoCount/$maxPhotos photos"
                    else "0/$maxPhotos photos",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (atLimit) Color(0xFFF59E0B) else GccGreen,
                )
            }

            // LiDAR indicator is only shown for live camera capture. Static
            // library picks must stay visually distinct from depth-backed scans.
            if (hasDepthCapture || canCaptureDepth) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF3B82F6).copy(alpha = 0.85f))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text(
                        if (hasDepthCapture) "LiDAR 3D" else "LiDAR Ready",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                    )
                }
            }
        }

        // Thumbnail strip — floats above the bottom chrome instead of
        // reserving layout space below the preview. Hidden in environment-scan
        // mode: derived imagery is processed after the fact on the backend.
        if (capturedPhotos.isNotEmpty() && !environmentScanMode) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(start = 10.dp, bottom = 124.dp),
            ) {
                items(capturedPhotos.size) { index ->
                    val photo = capturedPhotos[index]
                    val bitmap = remember(photo) { decodeImageBitmap(photo.bytes) }
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF2A2A3E)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = "Photo ${index + 1}",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Text(
                                "${index + 1}",
                                style = MaterialTheme.typography.titleMedium,
                                color = GccGreen,
                            )
                        }
                        if (onRemovePhoto != null) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(Color.Red.copy(alpha = 0.8f))
                                    .clickable { onRemovePhoto(index) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "\u2715",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                )
                            }
                        }

                        // "3D" badge for depth-enhanced photos
                        if (photo.depthAnalysis?.hasDepth == true) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(2.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color(0xFF3B82F6).copy(alpha = 0.9f))
                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                            ) {
                                Text(
                                    "3D",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White,
                                )
                            }
                        }
                    }
                }
            }
        }


        // Library button only \u2014 regular capture is tap-on-preview, no shutter button.
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Library button — only visible for admins (anti-fraud: users must use live capture)
            if (allowLibraryPick) {
                OutlinedButton(
                    onClick = {
                        if (!atLimit) {
                            coroutineScope.launch {
                                val photo = pickFromLibrary()
                                if (photo != null) onCapture(photo)
                            }
                        }
                    },
                    modifier = Modifier.size(72.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = if (atLimit) Color.Gray else GccGreen),
                    enabled = !isCapturing && !atLimit,
                ) {
                    Text(
                        "\uD83D\uDDBC",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                }
            }
        }
    }
}
