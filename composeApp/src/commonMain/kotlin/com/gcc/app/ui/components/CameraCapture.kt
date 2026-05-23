package com.gcc.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
 * Shows a live camera preview with:
 * - Photo count badge (top-left)
 * - Circular capture button + library button (bottom)
 * - Optional mini-map overlay (bottom-end)
 * - Thumbnail strip of captured photos
 *
 * Used by both SpotScreen camera phase and admin spot creation.
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
    allowLibraryPick: Boolean = false,
) {
    val cameraManager = remember { CameraManager() }
    val coroutineScope = rememberCoroutineScope()
    var isCapturing by remember { mutableStateOf(false) }
    val photoCount = capturedPhotos.size
    val atLimit = photoCount >= maxPhotos
    val hasDepthCapture = capturedPhotos.any { it.depthAnalysis?.hasDepth == true }
    val canCaptureDepth = !allowLibraryPick && capturedPhotos.isEmpty() && hasLiDAR()

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Live camera preview with overlays
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp)),
        ) {
            CameraPreview(modifier = Modifier.fillMaxSize())

            // Mini-map overlay (bottom-end)
            if (showMiniMap && currentLocation != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(10.dp)
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

            // Photo count badge (bottom-start) with limit indicator
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp)
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

        // Thumbnail strip of captured photos
        if (capturedPhotos.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
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

        Spacer(modifier = Modifier.height(12.dp))

        // Capture + Library buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Capture button
            OutlinedButton(
                onClick = {
                    if (!isCapturing && !atLimit) {
                        isCapturing = true
                        coroutineScope.launch {
                            val photo = cameraManager.capturePhoto()
                            if (photo != null) onCapture(photo)
                            isCapturing = false
                        }
                    }
                },
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = if (atLimit) Color.Gray else GccGreen),
                enabled = !isCapturing && !atLimit,
            ) {
                if (isCapturing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = GccGreen,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(
                        "\uD83D\uDCF8",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                }
            }

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
