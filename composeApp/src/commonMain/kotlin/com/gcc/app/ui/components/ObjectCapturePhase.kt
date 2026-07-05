package com.gcc.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcc.app.platform.ObjectCaptureController
import com.gcc.app.platform.ObjectCaptureOutcome
import com.gcc.app.platform.ObjectCaptureStage
import com.gcc.app.ui.theme.GccGreen

/**
 * Full-screen guided 3D capture: hosts the native Object Capture view with
 * one stage-driven action button (Start detection → Start capture → Finish →
 * reconstruction progress) and a Cancel escape. This is an optional sub-flow
 * of the environment scan — the scan session itself stays active behind it.
 */
@Composable
fun ObjectCapturePhase(
    onDone: (ObjectCaptureOutcome) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val controller = remember { ObjectCaptureController() }
    val stage by controller.stage.collectAsState()
    val progress by controller.progress.collectAsState()
    val outcome by controller.outcome.collectAsState()

    LaunchedEffect(outcome) {
        outcome?.let { onDone(it) }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        com.gcc.app.platform.ObjectCaptureHostView(
            controller = controller,
            modifier = Modifier.fillMaxSize(),
        )

        // Stage guidance (top-center).
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 52.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text = when (stage) {
                    ObjectCaptureStage.UNSUPPORTED -> "3D capture isn't available on this device"
                    ObjectCaptureStage.INITIALIZING -> "Starting 3D capture…"
                    ObjectCaptureStage.READY -> "Point the camera at the pile, then start detection"
                    ObjectCaptureStage.DETECTING -> "Fit the box around the pile, then start capturing"
                    ObjectCaptureStage.CAPTURING -> "Orbit the pile slowly — cover every side"
                    ObjectCaptureStage.RECONSTRUCTING -> "Building the 3D model… ${(progress * 100).toInt()}%"
                    ObjectCaptureStage.COMPLETED -> "Model captured ✓"
                    ObjectCaptureStage.FAILED -> "Capture failed — you can try again"
                },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val (label, action) = when (stage) {
                ObjectCaptureStage.READY -> "Start detection" to { controller.advance() }
                ObjectCaptureStage.DETECTING -> "Start capturing" to { controller.advance() }
                ObjectCaptureStage.CAPTURING -> "Finish capture ✓" to { controller.finishCapture() }
                else -> null to null
            }
            if (label != null && action != null) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(28.dp))
                        .background(GccGreen)
                        .clickable { action() }
                        .padding(horizontal = 28.dp, vertical = 16.dp),
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
            }
            val cancelLabel = when (stage) {
                ObjectCaptureStage.RECONSTRUCTING -> null // let it finish
                ObjectCaptureStage.FAILED, ObjectCaptureStage.UNSUPPORTED -> "Close"
                else -> "Cancel 3D scan"
            }
            if (cancelLabel != null) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.White.copy(alpha = 0.15f))
                        .clickable {
                            controller.cancel()
                            onCancel()
                        }
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                ) {
                    Text(cancelLabel, style = MaterialTheme.typography.labelLarge, color = Color.White)
                }
            }
        }
    }
}
