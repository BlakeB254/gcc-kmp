package com.gcc.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gcc.app.ui.components.GccLogo
import com.gcc.app.ui.map.chicagoCommunityAreas
import com.gcc.app.ui.theme.GccGreen
import com.gcc.app.ui.theme.GccGreenDark
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// ── Colors ──
private val GreyBase = Color(0xFF4A4A4A)
private val HotspotRed = Color(0xFFE53935)
private val OceanBlue = Color(0xFF1565C0)
private val SplashBackground = Color(0xFF111111)

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

// ── Continent outlines (normalized 0-1 within globe bounding box) ──
private data class Continent(val name: String, val points: List<Pair<Float, Float>>)

private val CONTINENTS = listOf(
    Continent("North America", listOf(
        0.18f to 0.18f, 0.28f to 0.15f, 0.35f to 0.22f, 0.38f to 0.30f,
        0.35f to 0.38f, 0.30f to 0.42f, 0.25f to 0.38f, 0.20f to 0.35f, 0.15f to 0.28f,
    )),
    Continent("South America", listOf(
        0.30f to 0.48f, 0.35f to 0.50f, 0.38f to 0.58f, 0.36f to 0.68f,
        0.32f to 0.78f, 0.28f to 0.82f, 0.26f to 0.75f, 0.25f to 0.65f, 0.27f to 0.55f,
    )),
    Continent("Europe", listOf(
        0.47f to 0.18f, 0.52f to 0.15f, 0.55f to 0.18f, 0.54f to 0.25f,
        0.50f to 0.30f, 0.47f to 0.28f, 0.45f to 0.22f,
    )),
    Continent("Africa", listOf(
        0.47f to 0.32f, 0.53f to 0.30f, 0.58f to 0.38f, 0.57f to 0.50f,
        0.54f to 0.62f, 0.50f to 0.68f, 0.46f to 0.60f, 0.44f to 0.48f, 0.45f to 0.38f,
    )),
    Continent("Asia", listOf(
        0.55f to 0.12f, 0.65f to 0.10f, 0.78f to 0.15f, 0.82f to 0.25f,
        0.80f to 0.35f, 0.72f to 0.42f, 0.65f to 0.38f, 0.58f to 0.32f, 0.55f to 0.25f,
    )),
    Continent("Australia", listOf(
        0.72f to 0.58f, 0.80f to 0.55f, 0.85f to 0.60f, 0.83f to 0.68f,
        0.78f to 0.72f, 0.72f to 0.68f, 0.70f to 0.62f,
    )),
)

@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val masterProgress = remember { Animatable(0f) }
    val splashAlpha = remember { Animatable(1f) }
    var skipped by remember { mutableStateOf(false) }

    // Master animation: 0 -> 1 over 8 seconds
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
        if (progress >= 0.8125f && logoScale.value == 0f) {
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
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val p = progress

            // Background
            drawRect(SplashBackground)

            when {
                // ── Phase 1: North Lawndale Close-Up (0 - 0.25) ──
                p < 0.25f -> {
                    val phase1Progress = p / 0.25f
                    drawPhase1(phase1Progress, w, h)
                }

                // ── Phase 2: Zoom Out (0.25 - 0.5625) ──
                p < 0.5625f -> {
                    val phase2Progress = (p - 0.25f) / 0.3125f
                    drawPhase2(phase2Progress, w, h, sortedAreas)
                }

                // ── Phase 3: Globe Morph (0.5625 - 0.8125) ──
                p < 0.8125f -> {
                    val phase3Progress = (p - 0.5625f) / 0.25f
                    drawPhase3(phase3Progress, w, h)
                }

                // ── Phase 4: Logo + Exit (0.8125 - 1.0) ──
                else -> {
                    val phase4Progress = (p - 0.8125f) / 0.1875f
                    drawPhase4(phase4Progress, w, h)
                }
            }
        }

        // Logo overlay in Phase 4
        if (progress >= 0.8125f) {
            val phase4Progress = ((progress - 0.8125f) / 0.1875f).coerceIn(0f, 1f)
            val textAlpha = ((phase4Progress - 0.4f) / 0.6f).coerceIn(0f, 1f)

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                GccLogo(
                    size = 80.dp,
                    showGlow = true,
                    modifier = Modifier
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
                    modifier = Modifier.offset(y = 60.dp),
                )
            }
        }
    }
}

// ── Phase 1: North Lawndale Close-Up ──
private fun DrawScope.drawPhase1(progress: Float, w: Float, h: Float) {
    val polyWidth = w * 0.70f
    val nlPath = buildNlPath(
        polygon = NL_POLYGON,
        targetWidth = polyWidth,
        center = Offset(w / 2f, h / 2f),
    )
    val bounds = nlPath.getBounds()

    // Grey base fill
    drawPath(nlPath, color = GreyBase)

    // Green sweep left-to-right
    val sweepX = bounds.left + bounds.width * progress
    clipRect(left = bounds.left, top = bounds.top, right = sweepX, bottom = bounds.bottom) {
        drawPath(nlPath, color = GccGreen)
    }

    // Hotspot dots
    NL_HOTSPOTS.forEachIndexed { i, (lat, lng) ->
        val pos = latLngToCanvasPos(
            lat, lng,
            NL_POLYGON,
            targetWidth = polyWidth,
            center = Offset(w / 2f, h / 2f),
        )
        val hotspotSweepNorm = ((pos.x - bounds.left) / bounds.width).coerceIn(0f, 1f)
        val cleaned = progress > hotspotSweepNorm

        // Pulse radius via sin wave
        val baseRadius = w * 0.018f
        val pulsePhase = sin(progress * PI.toFloat() * 6f + i * 1.5f)
        val radius = if (cleaned) {
            baseRadius * (1f - (progress - hotspotSweepNorm).coerceIn(0f, 0.3f) / 0.3f * 0.5f)
        } else {
            baseRadius + baseRadius * 0.3f * pulsePhase
        }

        val color = if (cleaned) {
            lerp(HotspotRed, GccGreen, ((progress - hotspotSweepNorm) / 0.2f).coerceIn(0f, 1f))
        } else {
            HotspotRed
        }

        drawCircle(color = color, radius = radius, center = pos)
    }

    // Border
    drawPath(nlPath, color = Color.White.copy(alpha = 0.3f), style = Stroke(width = 2f))
}

// ── Phase 2: Zoom Out ──
private fun DrawScope.drawPhase2(
    progress: Float,
    w: Float,
    h: Float,
    sortedAreas: List<Pair<com.gcc.app.ui.map.CommunityArea, Double>>,
) {
    val scale = 1f - progress * 0.85f // 1.0 -> 0.15
    val center = Offset(w / 2f, h / 2f)

    // Phase 1 fading out during first 20% of phase 2
    val phase1Alpha = (1f - progress / 0.2f).coerceIn(0f, 1f)

    withTransform({
        translate(left = center.x * (1f - scale), top = center.y * (1f - scale))
        scale(scale, scale, pivot = Offset.Zero)
    }) {
        // Geo extents for mapping all areas
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

        // Radial cleaning wave from NL centroid
        val maxDist = sortedAreas.maxOf { it.second }.toFloat()

        // Draw surrounding areas
        sortedAreas.forEach { (area, dist) ->
            val areaPath = buildAreaPath(area.polygon)
            val distNorm = (dist.toFloat() / maxDist).coerceIn(0f, 1f)
            val cleanThreshold = distNorm
            val cleaned = progress > cleanThreshold

            val fillColor = if (cleaned) {
                lerp(GreyBase, GccGreen, ((progress - cleanThreshold) / 0.15f).coerceIn(0f, 1f))
            } else {
                GreyBase
            }
            drawPath(areaPath, color = fillColor)
            drawPath(areaPath, color = Color.White.copy(alpha = 0.15f), style = Stroke(width = 1f))

            // Area hotspot at centroid
            val centLat = area.polygon.map { it.first }.average()
            val centLng = area.polygon.map { it.second }.average()
            val centPos = toCanvas(centLat, centLng)
            val dotRadius = w * 0.008f
            val pulsePhase = sin(progress * PI.toFloat() * 4f + dist.toFloat() * 10f)

            val dotColor = if (cleaned) {
                lerp(HotspotRed, GccGreen, ((progress - cleanThreshold) / 0.15f).coerceIn(0f, 1f))
            } else {
                HotspotRed
            }
            val r = if (cleaned) dotRadius * 0.6f else dotRadius + dotRadius * 0.25f * pulsePhase
            drawCircle(color = dotColor, radius = r, center = centPos)
        }

        // North Lawndale always on top, always green
        val nlPath = buildAreaPath(NL_POLYGON)
        drawPath(nlPath, color = GccGreen)
        drawPath(nlPath, color = GccGreenDark, style = Stroke(width = 2f))
    }
}

// ── Phase 3: Globe Morph ──
private fun DrawScope.drawPhase3(progress: Float, w: Float, h: Float) {
    // Community areas fade out in first 30%
    val areaAlpha = (1f - progress / 0.3f).coerceIn(0f, 1f)
    if (areaAlpha > 0f) {
        // Draw a fading small cluster hint
        val tinyScale = 0.15f
        val center = Offset(w / 2f, h * 0.42f)
        // Just a small green dot representing the fading map
        drawCircle(
            color = GccGreen.copy(alpha = areaAlpha * 0.5f),
            radius = w * 0.05f * tinyScale,
            center = center,
        )
    }

    // Globe
    val globeRadius = w * 0.35f
    val globeCenter = Offset(w / 2f, h * 0.42f)
    val globeAlpha = ((progress - 0.1f) / 0.3f).coerceIn(0f, 1f)

    // Ocean
    drawCircle(
        color = OceanBlue.copy(alpha = globeAlpha),
        radius = globeRadius,
        center = globeCenter,
    )

    // Clip to globe for continents
    val globeClipPath = Path().apply {
        addOval(
            Rect(
                left = globeCenter.x - globeRadius,
                top = globeCenter.y - globeRadius,
                right = globeCenter.x + globeRadius,
                bottom = globeCenter.y + globeRadius,
            ),
        )
    }

    clipPath(globeClipPath) {
        // Continents with staggered fade-in
        val staggerDelayMs = 80f / 2000f // 80ms out of ~2000ms phase duration normalized
        CONTINENTS.forEachIndexed { i, continent ->
            val staggerStart = i * staggerDelayMs
            val continentAlpha = ((progress - 0.2f - staggerStart) / 0.3f).coerceIn(0f, 1f) * globeAlpha

            if (continentAlpha > 0f) {
                val path = Path()
                val globeLeft = globeCenter.x - globeRadius
                val globeTop = globeCenter.y - globeRadius
                val globeDiameter = globeRadius * 2f

                continent.points.forEachIndexed { idx, (nx, ny) ->
                    val x = globeLeft + nx * globeDiameter
                    val y = globeTop + ny * globeDiameter
                    if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()

                drawPath(path, color = GccGreen.copy(alpha = continentAlpha))
            }
        }
    }

    // Globe outline ring
    drawCircle(
        color = Color.White.copy(alpha = globeAlpha * 0.4f),
        radius = globeRadius,
        center = globeCenter,
        style = Stroke(width = 2.5f),
    )

    // Subtle highlight arc (upper-left)
    val highlightPath = Path().apply {
        val startAngle = -150f
        val sweepAngle = 80f
        addArc(
            oval = Rect(
                left = globeCenter.x - globeRadius * 0.92f,
                top = globeCenter.y - globeRadius * 0.92f,
                right = globeCenter.x + globeRadius * 0.92f,
                bottom = globeCenter.y + globeRadius * 0.92f,
            ),
            startAngleDegrees = startAngle,
            sweepAngleDegrees = sweepAngle,
        )
    }
    drawPath(
        highlightPath,
        color = Color.White.copy(alpha = globeAlpha * 0.2f),
        style = Stroke(width = 3f, cap = StrokeCap.Round),
    )
}

// ── Phase 4: Logo + Exit ──
private fun DrawScope.drawPhase4(
    progress: Float,
    w: Float,
    h: Float,
) {
    // Globe shrinks and moves up
    val baseRadius = w * 0.35f
    val targetRadius = w * 0.22f
    val globeRadius = baseRadius + (targetRadius - baseRadius) * progress
    val baseY = h * 0.42f
    val targetY = h * 0.35f
    val globeY = baseY + (targetY - baseY) * progress
    val globeCenter = Offset(w / 2f, globeY)

    // Ocean
    drawCircle(color = OceanBlue, radius = globeRadius, center = globeCenter)

    // Globe clip
    val globeClipPath = Path().apply {
        addOval(
            Rect(
                left = globeCenter.x - globeRadius,
                top = globeCenter.y - globeRadius,
                right = globeCenter.x + globeRadius,
                bottom = globeCenter.y + globeRadius,
            ),
        )
    }

    clipPath(globeClipPath) {
        CONTINENTS.forEach { continent ->
            val path = Path()
            val globeLeft = globeCenter.x - globeRadius
            val globeTop = globeCenter.y - globeRadius
            val globeDiameter = globeRadius * 2f

            continent.points.forEachIndexed { idx, (nx, ny) ->
                // X shift for rotation illusion
                val shiftedX = nx + progress * 0.4f * (globeRadius / (w * 0.35f))
                val x = globeLeft + shiftedX * globeDiameter
                val y = globeTop + ny * globeDiameter
                if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()

            drawPath(path, color = GccGreen)
        }
    }

    // Globe outline
    drawCircle(
        color = Color.White.copy(alpha = 0.35f),
        radius = globeRadius,
        center = globeCenter,
        style = Stroke(width = 2f),
    )

    // Highlight arc
    val highlightPath = Path().apply {
        addArc(
            oval = Rect(
                left = globeCenter.x - globeRadius * 0.92f,
                top = globeCenter.y - globeRadius * 0.92f,
                right = globeCenter.x + globeRadius * 0.92f,
                bottom = globeCenter.y + globeRadius * 0.92f,
            ),
            startAngleDegrees = -150f,
            sweepAngleDegrees = 80f,
        )
    }
    drawPath(
        highlightPath,
        color = Color.White.copy(alpha = 0.15f),
        style = Stroke(width = 2.5f, cap = StrokeCap.Round),
    )
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
