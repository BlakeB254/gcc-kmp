package com.gcc.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gcc.app.platform.isAndroidHost
import com.gcc.app.ui.components.GccLogo
import gcc_kmp.composeapp.generated.resources.Res
import gcc_kmp.composeapp.generated.resources.uic_occ_logo
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource

// The UIC Office of Community Collaboration bought the Android handset and a
// year of service. Without a physical device there is no Android build at all,
// so the credit runs for as long as the service they paid for: one year from
// the 2026-09-15 handover. After that this screen stops appearing on its own —
// nobody has to remember to remove it.
private const val CREDIT_EXPIRES_EPOCH_MS = 1_820_966_400_000L // 2027-09-15T00:00:00Z

private val CreditBackground = Color(0xFF0A0E0A)

/** True while the donor credit should still be shown. Android only, and only until it lapses. */
fun shouldShowUicCredit(nowMs: Long = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()): Boolean =
    isAndroidHost() && nowMs < CREDIT_EXPIRES_EPOCH_MS

/**
 * Donor credit shown straight after the splash on Android.
 *
 * Reads in three beats — ours, the platform, then the thank-you — so the GCC
 * mark lands first and UIC's sits beside the sentence that explains it. The UIC
 * lockup is deliberately much smaller than the GCC mark: this is an
 * acknowledgement, not co-branding, and it must not read as a joint product.
 */
@Composable
fun UicCreditScreen(onFinished: () -> Unit) {
    val gccAlpha = remember { Animatable(0f) }
    val gccScale = remember { Animatable(0.88f) }
    val androidAlpha = remember { Animatable(0f) }
    val thanksAlpha = remember { Animatable(0f) }
    val screenAlpha = remember { Animatable(1f) }

    var finished by remember { mutableStateOf(false) }
    fun finishOnce(): Boolean {
        if (finished) return false
        finished = true
        return true
    }

    LaunchedEffect(Unit) {
        gccAlpha.animateTo(1f, tween(420))
        gccScale.animateTo(1f, tween(520))
        delay(260)
        androidAlpha.animateTo(1f, tween(360))
        delay(520)
        thanksAlpha.animateTo(1f, tween(460))
        delay(2200)
        if (finishOnce()) {
            screenAlpha.animateTo(0f, tween(340))
            onFinished()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = screenAlpha.value }
            .background(CreditBackground)
            // Tap to skip, matching the splash's own affordance.
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    if (finishOnce()) onFinished()
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 36.dp),
        ) {
            // ── Beat one: ours, and the largest thing on screen ──
            GccLogo(
                size = 112.dp,
                showGlow = true,
                modifier = Modifier.graphicsLayer {
                    alpha = gccAlpha.value
                    scaleX = gccScale.value
                    scaleY = gccScale.value
                },
            )
            Text(
                text = "Global Community Cleaners",
                style = TextStyle(
                    color = Color.White,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    textAlign = TextAlign.Center,
                ),
                modifier = Modifier
                    .padding(top = 18.dp)
                    .graphicsLayer { alpha = gccAlpha.value },
            )

            // ── Beat two: the platform ──
            Text(
                text = "on Android",
                style = TextStyle(
                    color = Color(0xFF7BD08F),
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                ),
                modifier = Modifier
                    .padding(top = 14.dp)
                    .graphicsLayer { alpha = androidAlpha.value },
            )

            // ── Beat three: the thank-you, with UIC's mark beside it ──
            Text(
                text = "Made possible by a generous Android donation from the " +
                    "University of Illinois Chicago, Office of Community Collaboration.",
                style = TextStyle(
                    color = Color(0xFFBFC8BF),
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    textAlign = TextAlign.Center,
                ),
                modifier = Modifier
                    .padding(top = 46.dp)
                    .graphicsLayer { alpha = thanksAlpha.value },
            )
            Image(
                painter = painterResource(Res.drawable.uic_occ_logo),
                contentDescription = "University of Illinois Chicago, Office of Community Collaboration",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .padding(top = 20.dp)
                    // Roughly a quarter the height of the GCC mark above — the
                    // credit must never read as co-branding.
                    .height(30.dp)
                    .graphicsLayer { alpha = thanksAlpha.value },
            )
        }
    }
}
