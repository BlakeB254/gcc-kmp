package com.gcc.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gcc.app.ui.components.GccLogo
import com.gcc.app.ui.map.chicagoCommunityAreas
import com.gcc.app.ui.theme.GccGreen
import com.gcc.app.ui.theme.GccGreenDark
import gcc_kmp.composeapp.generated.resources.Res
import io.github.alexzhirkevich.compottie.Compottie
import io.github.alexzhirkevich.compottie.LottieCompositionSpec
import io.github.alexzhirkevich.compottie.rememberLottieComposition
import io.github.alexzhirkevich.compottie.rememberLottiePainter
import org.jetbrains.compose.resources.ExperimentalResourceApi
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// ── Timeline thresholds ──
private const val PHASE1_END = 0.25f        // 0-2s
private const val PHASE2_END = 0.5625f       // 2-4.5s
private const val PHASE3_START = 0.5625f     // 4.5s
private const val PHASE3_END = 0.8125f       // 6.5s
private const val PHASE4_START = 0.8125f     // 6.5s

// ── Colors ──
private val GreyBase = Color(0xFF4A4A4A)
private val HotspotRed = Color(0xFFE53935)
private val SplashBackground = Color(0xFF0A0E0A)
private val GlowGreen = Color(0xFF2E7D32)

// ── North Lawndale polygon (area #29) ──
private val NL_POLYGON = listOf(
    41.8750 to -87.7410,
    41.8750 to -87.6920,
    41.8600 to -87.6920,
    41.8440 to -87.6950,
    41.8440 to -87.7350,
    41.8550 to -87.7410,
)
private const val NL_CENTROID_LAT = 41.8595
private const val NL_CENTROID_LNG = -87.7165

// ── Hotspot positions within NL (lat, lng) ──
private val NL_HOTSPOTS = listOf(
    41.870 to -87.720,
    41.858 to -87.705,
    41.850 to -87.730,
    41.862 to -87.710,
)

@OptIn(ExperimentalResourceApi::class)
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val masterProgress = remember { Animatable(0f) }
    val splashAlpha = remember { Animatable(1f) }
    var skipped by remember { mutableStateOf(false) }

    // ── Lottie globe composition ──
    val globeComposition by rememberLottieComposition {
        LottieCompositionSpec.JsonString(
            Res.readBytes("files/spinning_globe.json").decodeToString()
        )
    }

    // Master animation: 0 → 1 over 8 seconds
    LaunchedEffect(Unit) {
        masterProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 8000, easing = LinearEasing),
        )
        // Animation complete — fade out
        splashAlpha.animateTo(0f, tween(500))
        onFinished()
    }

    // Skip handler
    LaunchedEffect(skipped) {
        if (skipped) {
            splashAlpha.animateTo(0f, tween(300))
            onFinished()
        }
    }

    // Pre-sort community areas by distance from NL centroid
    val sortedAreas = remember {
        chicagoCommunityAreas
            .filter { it.number != 29 }
            .map { area ->
                val centLat = area.polygon.map { it.first }.average()
                val centLng = area.polygon.map { it.second }.average()
                val dist = sqrt(
                    (centLat - NL_CENTROID_LAT).pow(2) +
                        (centLng - NL_CENTROID_LNG).pow(2),
                )
                area to dist
            }
            .sortedBy { it.second }
            .take(24)
    }

    // Logo scale for phase 4 spring bounce
    val logoScale = remember { Animatable(0f) }
    val progress = masterProgress.value

    LaunchedEffect(progress) {
        if (progress >= PHASE4_START && logoScale.value == 0f) {
            logoScale.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = 0.5f,
                    stiffness = 300f,
                ),
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = splashAlpha.value }
            .pointerInput(Unit) {
                detectTapGestures { skipped = true }
            },
    ) {
        // ── Canvas layer: Background + Phases 1-2 + transition effects ──
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val p = progress

            // Background
            drawRect(SplashBackground)

            when {
                // ── Phase 1: North Lawndale Close-Up (0 - 0.25) ──
                p < PHASE1_END -> {
                    val phase1Progress = p / PHASE1_END
                    drawPhase1(phase1Progress, w, h)
                }

                // ── Phase 2: Zoom Out (0.25 - 0.5625) ──
                p < PHASE2_END -> {
                    val phase2Progress = (p - PHASE1_END) / (PHASE2_END - PHASE1_END)
                    drawPhase2(phase2Progress, w, h, sortedAreas)
                }

                // ── Phase 3-4: Dark background with radial glow ──
                else -> {
                    val glowProgress = ((p - PHASE3_START) / 0.1f).coerceIn(0f, 1f)
                    // Radial green glow behind the globe
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                GlowGreen.copy(alpha = 0.15f * glowProgress),
                                GlowGreen.copy(alpha = 0.05f * glowProgress),
                                Color.Transparent,
                            ),
                            center = Offset(w / 2f, h * 0.40f),
                            radius = w * 0.6f,
                        ),
                        radius = w * 0.6f,
                        center = Offset(w / 2f, h * 0.40f),
                    )
                }
            }

            // Particle-like green dots floating outward during transition
            if (p in PHASE3_START..PHASE3_END) {
                val particleProgress = ((p - PHASE3_START) / (PHASE3_END - PHASE3_START))
                drawTransitionParticles(particleProgress, w, h)
            }
        }

        // ── Lottie Globe layer (phases 3-4) ──
        val globeAlpha = when {
            progress < PHASE3_START -> 0f
            progress < PHASE3_START + 0.0625f -> // fade in over ~500ms
                ((progress - PHASE3_START) / 0.0625f).coerceIn(0f, 1f)
            else -> 1f
        }

        // Globe size transitions: larger during phase 3, shrinks for logo in phase 4
        val globeSizeDp = when {
            progress < PHASE4_START -> 280.dp
            else -> {
                val shrink = ((progress - PHASE4_START) / (1f - PHASE4_START)).coerceIn(0f, 1f)
                (280 - (shrink * 80).toInt()).dp // 280dp → 200dp
            }
        }
        val globeOffsetY = when {
            progress < PHASE4_START -> (-20).dp
            else -> {
                val shift = ((progress - PHASE4_START) / (1f - PHASE4_START)).coerceIn(0f, 1f)
                ((-20) - (shift * 50).toInt()).dp // moves up for logo
            }
        }

        if (globeAlpha > 0f) {
            Image(
                painter = rememberLottiePainter(
                    composition = globeComposition,
                    iterations = Compottie.IterateForever,
                ),
                contentDescription = null,
                modifier = Modifier
                    .size(globeSizeDp)
                    .align(Alignment.Center)
                    .offset(y = globeOffsetY)
                    .graphicsLayer { alpha = globeAlpha },
                contentScale = ContentScale.Fit,
            )
        }

        // ── Logo + Text overlay (phase 4) ──
        if (progress >= PHASE4_START) {
            val phase4Progress = ((progress - PHASE4_START) / (1f - PHASE4_START)).coerceIn(0f, 1f)
            val textAlpha = ((phase4Progress - 0.3f) / 0.5f).coerceIn(0f, 1f)

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                GccLogo(
                    size = 80.dp,
                    showGlow = true,
                    modifier = Modifier
                        .offset(y = 50.dp)
                        .graphicsLayer {
                            scaleX = logoScale.value
                            scaleY = logoScale.value
                        },
                )

                Text(
                    text = "Global Community Cleaners",
                    style = TextStyle(
                        color = Color.White.copy(alpha = textAlpha),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                    ),
                    modifier = Modifier.offset(y = 105.dp),
                )
            }
        }
    }
}

// ── Phase 1: North Lawndale Close-Up ──
private fun DrawScope.drawPhase1(progress: Float, w: Float, h: Float) {
    val center = Offset(w / 2f, h / 2f)

    // Subtle radial glow behind NL
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                GlowGreen.copy(alpha = 0.12f * progress),
                Color.Transparent,
            ),
            center = center,
            radius = w * 0.5f,
        ),
        radius = w * 0.5f,
        center = center,
    )

    val polyWidth = w * 0.70f
    val nlPath = buildNlPath(
        polygon = NL_POLYGON,
        targetWidth = polyWidth,
        center = center,
    )
    val bounds = nlPath.getBounds()

    // Grey base fill
    drawPath(nlPath, color = GreyBase)

    // Green sweep left-to-right with eased progress
    val easedSweep = progress * progress * (3f - 2f * progress) // smoothstep
    val sweepX = bounds.left + bounds.width * easedSweep
    clipRect(left = bounds.left, top = bounds.top, right = sweepX, bottom = bounds.bottom) {
        drawPath(nlPath, color = GccGreen)
    }

    // Hotspot dots
    NL_HOTSPOTS.forEachIndexed { i, (lat, lng) ->
        val pos = latLngToCanvasPos(
            lat, lng,
            NL_POLYGON,
            targetWidth = polyWidth,
            center = center,
        )
        val hotspotSweepNorm = ((pos.x - bounds.left) / bounds.width).coerceIn(0f, 1f)
        val cleaned = easedSweep > hotspotSweepNorm

        // Pulse radius via sin wave
        val baseRadius = w * 0.018f
        val pulsePhase = sin(progress * PI.toFloat() * 6f + i * 1.5f)
        val radius = if (cleaned) {
            baseRadius * (1f - (easedSweep - hotspotSweepNorm).coerceIn(0f, 0.3f) / 0.3f * 0.5f)
        } else {
            baseRadius + baseRadius * 0.3f * pulsePhase
        }

        val color = if (cleaned) {
            lerp(HotspotRed, GccGreen, ((easedSweep - hotspotSweepNorm) / 0.2f).coerceIn(0f, 1f))
        } else {
            HotspotRed
        }

        // Glow ring around active hotspots
        if (!cleaned) {
            drawCircle(
                color = HotspotRed.copy(alpha = 0.2f + 0.1f * pulsePhase),
                radius = radius * 2f,
                center = pos,
            )
        }

        drawCircle(color = color, radius = radius, center = pos)
    }

    // Border with slight glow
    drawPath(nlPath, color = Color.White.copy(alpha = 0.25f), style = Stroke(width = 2f))
    drawPath(nlPath, color = GccGreen.copy(alpha = 0.1f), style = Stroke(width = 6f))
}

// ── Phase 2: Zoom Out ──
private fun DrawScope.drawPhase2(
    progress: Float,
    w: Float,
    h: Float,
    sortedAreas: List<Pair<com.gcc.app.ui.map.CommunityArea, Double>>,
) {
    // Smoother zoom: 1.0 -> 0.45 (not as extreme as before)
    val scale = 1f - progress * 0.55f
    val center = Offset(w / 2f, h / 2f)

    withTransform({
        translate(left = center.x * (1f - scale), top = center.y * (1f - scale))
        scale(scale, scale, pivot = Offset.Zero)
    }) {
        drawCommunityAreas(w, h, sortedAreas, progress)
    }
}

// Shared community area drawing logic
private fun DrawScope.drawCommunityAreas(
    w: Float,
    h: Float,
    sortedAreas: List<Pair<com.gcc.app.ui.map.CommunityArea, Double>>,
    cleanProgress: Float,
    globalAlpha: Float = 1f,
) {
    val allAreas = sortedAreas.map { it.first } + chicagoCommunityAreas.first { it.number == 29 }
    val allPoints = allAreas.flatMap { it.polygon }
    val minLat = allPoints.minOf { it.first }
    val maxLat = allPoints.maxOf { it.first }
    val minLng = allPoints.minOf { it.second }
    val maxLng = allPoints.maxOf { it.second }

    val latRange = maxLat - minLat
    val lngRange = maxLng - minLng
    val mapW = w * 0.85f
    val mapH = h * 0.85f
    val mapOffX = (w - mapW) / 2f
    val mapOffY = (h - mapH) / 2f

    fun toCanvas(lat: Double, lng: Double): Offset {
        val x = mapOffX + ((lng - minLng) / lngRange * mapW).toFloat()
        val y = mapOffY + ((maxLat - lat) / latRange * mapH).toFloat()
        return Offset(x, y)
    }

    fun buildAreaPath(polygon: List<Pair<Double, Double>>): Path {
        val path = Path()
        polygon.forEachIndexed { idx, (lat, lng) ->
            val pos = toCanvas(lat, lng)
            if (idx == 0) path.moveTo(pos.x, pos.y) else path.lineTo(pos.x, pos.y)
        }
        path.close()
        return path
    }

    val maxDist = sortedAreas.maxOf { it.second }.toFloat()

    // Draw surrounding areas
    sortedAreas.forEach { (area, dist) ->
        val areaPath = buildAreaPath(area.polygon)
        val distNorm = (dist.toFloat() / maxDist).coerceIn(0f, 1f)
        val cleaned = cleanProgress > distNorm

        val fillColor = if (cleaned) {
            lerp(GreyBase, GccGreen, ((cleanProgress - distNorm) / 0.15f).coerceIn(0f, 1f))
        } else {
            GreyBase
        }
        drawPath(areaPath, color = fillColor.copy(alpha = globalAlpha))
        drawPath(areaPath, color = Color.White.copy(alpha = 0.15f * globalAlpha), style = Stroke(width = 1f))

        // Area hotspot at centroid
        val centLat = area.polygon.map { it.first }.average()
        val centLng = area.polygon.map { it.second }.average()
        val centPos = toCanvas(centLat, centLng)
        val dotRadius = w * 0.008f
        val pulsePhase = sin(cleanProgress * PI.toFloat() * 4f + dist.toFloat() * 10f)

        val dotColor = if (cleaned) {
            lerp(HotspotRed, GccGreen, ((cleanProgress - distNorm) / 0.15f).coerceIn(0f, 1f))
        } else {
            HotspotRed
        }
        val r = if (cleaned) dotRadius * 0.6f else dotRadius + dotRadius * 0.25f * pulsePhase
        drawCircle(color = dotColor.copy(alpha = globalAlpha), radius = r, center = centPos)
    }

    // North Lawndale always on top, always green
    val nlPath = buildAreaPath(NL_POLYGON)
    drawPath(nlPath, color = GccGreen.copy(alpha = globalAlpha))
    drawPath(nlPath, color = GccGreenDark.copy(alpha = globalAlpha), style = Stroke(width = 2f))
}

// ── Transition particles (green dots floating outward) ──
private fun DrawScope.drawTransitionParticles(progress: Float, w: Float, h: Float) {
    val center = Offset(w / 2f, h * 0.40f)
    val particleCount = 12
    for (i in 0 until particleCount) {
        val angle = (2f * PI.toFloat() / particleCount) * i + progress * PI.toFloat() * 0.5f
        val baseRadius = w * 0.2f
        val expandRadius = baseRadius + progress * w * 0.25f
        val x = center.x + kotlin.math.cos(angle) * expandRadius
        val y = center.y + kotlin.math.sin(angle) * expandRadius
        val alpha = (1f - progress) * 0.4f
        val dotSize = w * 0.006f * (1f - progress * 0.5f)
        if (alpha > 0.01f) {
            drawCircle(
                color = GccGreen.copy(alpha = alpha),
                radius = dotSize,
                center = Offset(x, y),
            )
        }
    }
}

// ── Helpers ──

private fun buildNlPath(
    polygon: List<Pair<Double, Double>>,
    targetWidth: Float,
    center: Offset,
): Path {
    val lats = polygon.map { it.first }
    val lngs = polygon.map { it.second }
    val minLat = lats.min()
    val maxLat = lats.max()
    val minLng = lngs.min()
    val maxLng = lngs.max()
    val latRange = maxLat - minLat
    val lngRange = maxLng - minLng

    val aspect = latRange / lngRange
    val pxW = targetWidth
    val pxH = (pxW * aspect).toFloat()
    val offX = center.x - pxW / 2f
    val offY = center.y - pxH / 2f

    val path = Path()
    polygon.forEachIndexed { i, (lat, lng) ->
        val x = offX + ((lng - minLng) / lngRange * pxW).toFloat()
        val y = offY + ((maxLat - lat) / latRange * pxH).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

private fun latLngToCanvasPos(
    lat: Double,
    lng: Double,
    polygon: List<Pair<Double, Double>>,
    targetWidth: Float,
    center: Offset,
): Offset {
    val lats = polygon.map { it.first }
    val lngs = polygon.map { it.second }
    val minLat = lats.min()
    val maxLat = lats.max()
    val minLng = lngs.min()
    val maxLng = lngs.max()
    val latRange = maxLat - minLat
    val lngRange = maxLng - minLng

    val aspect = latRange / lngRange
    val pxW = targetWidth
    val pxH = (pxW * aspect).toFloat()
    val offX = center.x - pxW / 2f
    val offY = center.y - pxH / 2f

    val x = offX + ((lng - minLng) / lngRange * pxW).toFloat()
    val y = offY + ((maxLat - lat) / latRange * pxH).toFloat()
    return Offset(x, y)
}
