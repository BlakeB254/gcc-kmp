package com.gcc.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcc.app.platform.ShareManager
import com.gcc.app.ui.components.GccButton
import com.gcc.app.ui.components.GccButtonVariant
import com.gcc.app.ui.gamification.ShareableProfileCard
import com.gcc.app.ui.theme.GccGreen
import com.gcc.app.viewmodel.ProfileViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun UserCardScreen(
    onBack: () -> Unit = {},
) {
    val viewModel = koinInject<ProfileViewModel>()
    val shareManager = koinInject<ShareManager>()
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { viewModel.loadProfile() }

    val worker = uiState.worker

    // Capture state
    val graphicsLayer = rememberGraphicsLayer()
    var isSaving by remember { mutableStateOf(false) }
    var saveResult by remember { mutableStateOf<Boolean?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A)),
    ) {
        // Close button top-left
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .statusBarsPadding(),
        ) {
            Text(
                "\u2715",
                color = Color.White.copy(alpha = 0.6f),
                fontWeight = FontWeight.Bold,
            )
        }

        if (worker != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 21.dp, vertical = 55.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                ShareableProfileCard(
                    worker = worker,
                    badges = uiState.badges,
                    streakDays = uiState.streakDays,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .drawWithContent {
                            graphicsLayer.record {
                                this@drawWithContent.drawContent()
                            }
                            drawLayer(graphicsLayer)
                        },
                )

                Spacer(modifier = Modifier.height(21.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(13.dp),
                ) {
                    GccButton(
                        text = when {
                            isSaving -> "Saving..."
                            saveResult == true -> "Saved!"
                            saveResult == false -> "Failed"
                            else -> "Save to Photos"
                        },
                        onClick = {
                            if (!isSaving) {
                                scope.launch {
                                    isSaving = true
                                    try {
                                        val bitmap = graphicsLayer.toImageBitmap()
                                        val bytes = imageBitmapToBmpBytes(bitmap)
                                        saveResult = shareManager.saveImageToGallery(
                                            bytes, "gcc_card_${worker.signupRankings.global}"
                                        )
                                    } catch (e: Exception) {
                                        saveResult = false
                                    }
                                    isSaving = false
                                    delay(2000)
                                    saveResult = null
                                }
                            }
                        },
                        variant = GccButtonVariant.OUTLINED,
                        modifier = Modifier.weight(1f),
                    )
                    GccButton(
                        text = "Share",
                        onClick = {
                            val handle = worker.displayHandle?.let { "@$it" }
                                ?: worker.name.split(" ").first()
                            val badgeCount = uiState.badges.count { it.isUnlocked }
                            shareManager.shareText(
                                text = "$handle \u00b7 $badgeCount badges earned \u00b7 " +
                                    "Member #${worker.signupRankings.global} \u00b7 " +
                                    "Join the movement: https://globalcommunitycleaners.org" +
                                    (worker.referralCode?.let { "/refer/$it" } ?: ""),
                                subject = "My GCC Card",
                            )
                        },
                        variant = GccButtonVariant.PRIMARY,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = GccGreen)
            }
        }
    }
}

/**
 * Encode an ImageBitmap to BMP format bytes (pure Kotlin, no platform deps).
 *
 * Produces a 24-bit uncompressed BMP that Android BitmapFactory and iOS UIImage
 * can both decode natively.
 */
private fun imageBitmapToBmpBytes(bitmap: ImageBitmap): ByteArray {
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.readPixels(pixels)

    // 24-bit BMP: each row padded to 4-byte boundary
    val rowSize = ((w * 3 + 3) / 4) * 4
    val pixelDataSize = rowSize * h
    val fileSize = 54 + pixelDataSize

    val bmp = ByteArray(fileSize)

    // ── BMP File Header (14 bytes) ──
    bmp[0] = 0x42; bmp[1] = 0x4D // "BM"
    writeLE32(bmp, 2, fileSize)
    writeLE32(bmp, 10, 54) // pixel data offset

    // ── DIB Header — BITMAPINFOHEADER (40 bytes) ──
    writeLE32(bmp, 14, 40)  // header size
    writeLE32(bmp, 18, w)   // width
    writeLE32(bmp, 22, h)   // height
    writeLE16(bmp, 26, 1)   // color planes
    writeLE16(bmp, 28, 24)  // bits per pixel
    writeLE32(bmp, 34, pixelDataSize)

    // ── Pixel data: BGR, bottom-to-top row order ──
    for (y in 0 until h) {
        val srcY = h - 1 - y // BMP rows are bottom-up
        for (x in 0 until w) {
            val px = pixels[srcY * w + x] // ARGB from readPixels
            val offset = 54 + y * rowSize + x * 3
            bmp[offset] = (px and 0xFF).toByte()             // B
            bmp[offset + 1] = ((px shr 8) and 0xFF).toByte() // G
            bmp[offset + 2] = ((px shr 16) and 0xFF).toByte() // R
        }
    }

    return bmp
}

private fun writeLE32(buf: ByteArray, offset: Int, value: Int) {
    buf[offset] = value.toByte()
    buf[offset + 1] = (value shr 8).toByte()
    buf[offset + 2] = (value shr 16).toByte()
    buf[offset + 3] = (value shr 24).toByte()
}

private fun writeLE16(buf: ByteArray, offset: Int, value: Int) {
    buf[offset] = value.toByte()
    buf[offset + 1] = (value shr 8).toByte()
}
