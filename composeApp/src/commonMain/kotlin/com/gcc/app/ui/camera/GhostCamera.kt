package com.gcc.app.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gcc.app.platform.CameraPreview
import com.gcc.app.ui.theme.GccGreen

/**
 * Ghost camera composable with "before" photo overlay.
 *
 * The ghost overlay workflow:
 * 1. Worker arrives at checkpoint (within geofence)
 * 2. "Take Before Photo" → camera opens, full viewfinder
 * 3. Worker takes before photo → stored locally
 * 4. Worker cleans the area
 * 5. "Take After Photo" → camera opens WITH ghost overlay of before photo at 30% opacity
 * 6. Worker aligns to match composition → takes after photo
 * 7. Both photos uploaded to API
 *
 * Platform-specific camera rendering uses expect/actual CameraManager.
 * This composable provides the UI shell and ghost overlay logic.
 */
@Composable
fun GhostCamera(
    hasBeforePhoto: Boolean,
    isCapturingAfter: Boolean = false,
    onCapturePhoto: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // Camera preview — uses platform-specific CameraPreview composable
        CameraPreview(modifier = Modifier.fillMaxSize())

        // Ghost overlay indicator (when capturing "after" photo)
        if (isCapturingAfter && hasBeforePhoto) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(GccGreen.copy(alpha = 0.05f))
            ) {
                // The actual ghost overlay would render the before photo
                // at 30% opacity over the camera preview
                Text(
                    text = "Align with previous photo",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 60.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        // Bottom controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(32.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Cancel
            TextButton(onClick = onCancel) {
                Text("Cancel", color = Color.White)
            }

            // Capture button
            Button(
                onClick = onCapturePhoto,
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                contentPadding = PaddingValues(0.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .border(3.dp, GccGreen, CircleShape),
                )
            }

            // Spacer for symmetry
            Spacer(modifier = Modifier.width(48.dp))
        }

        // Photo type label
        Text(
            text = if (isCapturingAfter) "AFTER" else "BEFORE",
            color = if (isCapturingAfter) GccGreen else Color.White,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}
