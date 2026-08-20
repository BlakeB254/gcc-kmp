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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gcc.app.platform.SplashVideoPlayer
import com.gcc.app.platform.isReducedMotionEnabled
import com.gcc.app.ui.components.GccLogo
import com.gcc.app.ui.theme.GccGreen
import com.gcc.app.ui.theme.GccGreenDark
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import gcc_kmp.composeapp.generated.resources.Res
import io.github.alexzhirkevich.compottie.Compottie
import io.github.alexzhirkevich.compottie.LottieCompositionSpec
import io.github.alexzhirkevich.compottie.rememberLottieComposition
import io.github.alexzhirkevich.compottie.rememberLottiePainter
import org.jetbrains.compose.resources.ExperimentalResourceApi
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// ── Timeline thresholds (12 seconds total, overlapping crossfade windows) ──
// The four map phases overlap by FADE so each boundary is a crossfade, and
// every layer runs a scale settle (1.10x -> 1.0x) across its window — the
// combined read is one continuous pull-back:
//   neighborhood -> city -> state -> country -> world.
private const val PHASE1_END = 0.15f          // 0–1.8s      North Lawndale close-up
private const val PHASE2_START = 0.13f        // 1.56s
private const val PHASE2_END = 0.31f          // 3.72s       Chicago zoom-out (city)
private const val PHASE3_START = 0.29f        // 3.48s
private const val PHASE3_END = 0.47f          // 5.64s       Illinois (state)
private const val PHASE4_START = 0.45f        // 5.4s
private const val PHASE4_END = 0.68f          // 8.16s       Continental US (country)
private const val PHASE5_START = 0.66f        // 7.92s       Globe crossfade (world)
private const val PHASE5_END = 0.84f        // 10.08s
private const val PHASE6_START = 0.84f        // 10.08s      Logo reveal
private const val PHASE_FADE = 0.025f         // ~300ms crossfade between map phases

// ── Colors ──
private val GreyBase = Color(0xFF4A4A4A)
private val HotspotRed = Color(0xFFE53935)
private val SplashBackground = Color(0xFF0A0E0A)
private val GlowGreen = Color(0xFF2E7D32)

// ── North Lawndale centroid (for distance sorting) ──
private const val NL_CENTROID_LAT = 41.8595
private const val NL_CENTROID_LNG = -87.7165

// ── Hotspot positions within NL (lat, lng) ──
private val NL_HOTSPOTS = listOf(
    41.870 to -87.720,
    41.858 to -87.705,
    41.850 to -87.730,
    41.862 to -87.710,
)

// ── Chicago lat/lng for US map dot ──
private const val CHICAGO_LAT = 41.8781
private const val CHICAGO_LNG = -87.6298

// ── US bounding box ──
private const val US_MIN_LAT = 24.5
private const val US_MAX_LAT = 49.5
private const val US_MIN_LNG = -125.0
private const val US_MAX_LNG = -66.0

@OptIn(ExperimentalResourceApi::class)
@Composable
fun SplashScreen(onFinished: () -> Unit, shortMode: Boolean = false) {
    // F9: reduced-motion users get the static poster + logo, never the video
    // or the 12s animation.
    val reducedMotion = remember { isReducedMotionEnabled() }
    if (reducedMotion) {
        ReducedMotionSplash(onFinished)
        return
    }

    // Short mode (returning users) starts the master timeline at the logo
    // phase; the phase branches below key off `progress`, so phases 1–5 are
    // skipped cleanly with no special-case drawing code.
    val masterProgress = remember { Animatable(if (shortMode) PHASE6_START else 0f) }
    val splashAlpha = remember { Animatable(1f) }
    var skipped by remember { mutableStateOf(false) }

    // ── §5.2 video slot ──
    // null = attempt in flight, false = fell back to the Canvas animation,
    // true = real end-of-playback. Short mode never attempts the video.
    var videoOutcome by remember { mutableStateOf<Boolean?>(if (shortMode) false else null) }
    val videoStartedAt = remember { TimeSource.Monotonic.markNow() }

    // ── Load GeoJSON data ──
    var geoData by remember { mutableStateOf<SplashGeoData?>(null) }

    LaunchedEffect(Unit) {
        val chicagoJson = Res.readBytes("files/chicago_communities_real.json").decodeToString()
        val usJson = Res.readBytes("files/us_states.json").decodeToString()
        geoData = SplashGeoData(
            chicagoAreas = parseChicagoGeoJson(chicagoJson),
            usStates = parseUsStatesGeoJson(usJson),
        )
    }

    // ── Lottie globe composition ──
    val globeComposition by rememberLottieComposition {
        LottieCompositionSpec.JsonString(
            Res.readBytes("files/spinning_globe.json").decodeToString()
        )
    }

    // Master animation: 0 -> 1 over 12 seconds (waits for GeoJSON and for the
    // video slot to resolve to fallback; short mode runs only the final
    // ~1.9s logo phase from PHASE6_START).
    LaunchedEffect(geoData, videoOutcome) {
        if (geoData == null) return@LaunchedEffect
        if (videoOutcome != false) return@LaunchedEffect
        masterProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = if (shortMode) 1500 else 12000,
                easing = LinearEasing,
            ),
        )
        // Animation complete -- fade out
        splashAlpha.animateTo(0f, tween(500))
        onFinished()
    }

    // Real video end-of-playback: fade out and continue (mirrors the
    // animation-complete path above).
    LaunchedEffect(videoOutcome) {
        if (videoOutcome == true) {
            splashAlpha.animateTo(0f, tween(500))
            onFinished()
        }
    }

    // Skip handler
    LaunchedEffect(skipped) {
        if (skipped) {
            splashAlpha.animateTo(0f, tween(300))
            onFinished()
        }
    }

    // Pre-sort community areas by distance from NL centroid
    val sortedAreas = remember(geoData) {
        geoData?.chicagoAreas
            ?.filter { it.id != 29 }
            ?.map { area ->
                val outerRing = area.rings.firstOrNull() ?: return@map area to Double.MAX_VALUE
                val centLat = outerRing.map { it.first }.average()
                val centLng = outerRing.map { it.second }.average()
                val dist = sqrt(
                    (centLat - NL_CENTROID_LAT).pow(2) +
                        (centLng - NL_CENTROID_LNG).pow(2),
                )
                area to dist
            }
            ?.sortedBy { it.second }
    }

    // Pre-sort US states by distance from Chicago for green spread
    val sortedStates = remember(geoData) {
        geoData?.usStates?.map { state ->
            val ring = state.rings.firstOrNull() ?: return@map state to Double.MAX_VALUE
            val centLat = ring.map { it.first }.average()
            val centLng = ring.map { it.second }.average()
            val dist = sqrt(
                (centLat - CHICAGO_LAT).pow(2) +
                    (centLng - CHICAGO_LNG).pow(2),
            )
            state to dist
        }?.sortedBy { it.second }
    }

    // Illinois boundary for the state phase (from the US states GeoJSON)
    val illinois = remember(geoData) { geoData?.usStates?.find { it.name == "Illinois" } }

    // Logo scale for phase 6 spring bounce
    val logoScale = remember { Animatable(0f) }
    val progress = masterProgress.value

    LaunchedEffect(progress) {
        if (progress >= PHASE6_START && logoScale.value == 0f) {
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
        // ── §5.2 video surface (full mode only, until the slot resolves) ──
        // Stays composed after real end-of-playback so the end frame holds
        // through the fade-out; removed on fallback so the swap is seamless.
        if (videoOutcome != false) {
            SplashVideoPlayer(
                assetName = "splash-hero",
                onFinished = {
                    // The android/desktop actuals and a missing iOS resource
                    // finish within the first frames; real playback takes
                    // seconds. A fast finish means "no asset" → fall back to
                    // the Canvas animation instead of ending the splash.
                    videoOutcome = videoStartedAt.elapsedNow() >= 2.seconds
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Animation layers only once the video slot has resolved to fallback
        // (or short mode, which never attempts video) — otherwise the opaque
        // Canvas background and phase labels would cover the video surface.
        if (videoOutcome == false) {
        // ── Background layer: always; globe glow + particles during phase 5 ──
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val p = progress

            drawRect(SplashBackground)

            // Radial green glow behind the globe (phases 5+)
            if (p >= PHASE5_START) {
                val glowProgress = ((p - PHASE5_START) / 0.1f).coerceIn(0f, 1f)
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

            // Particle-like green dots floating outward during the globe transition
            if (p in PHASE5_START..PHASE5_END) {
                val particleProgress = (p - PHASE5_START) / (PHASE5_END - PHASE5_START)
                drawTransitionParticles(particleProgress, w, h)
            }
        }

        // ── Map phase layers (phases 1–4): each phase renders in its own
        // Canvas so the crossfade + scale-settle compositing comes from
        // graphicsLayer rather than hand-mixed color math inside the draw
        // functions. The settle (1.10x -> 1.0x, ease-out) is what makes the
        // sequence read as one continuous camera pull-back. ──
        val geo = geoData
        if (geo != null && progress < PHASE5_START) {

            // Phase 1: North Lawndale close-up (neighborhood)
            mapPhaseLayer(progress, 0f, PHASE1_END) { local, alpha, scale ->
                val nl = geo.northLawndale
                if (nl != null) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                this.alpha = alpha
                                scaleX = scale
                                scaleY = scale
                            },
                    ) {
                        drawPhase1NorthLawndale(local, size.width, size.height, nl)
                    }
                }
            }

            // Phase 2: Chicago zoom-out (city)
            mapPhaseLayer(progress, PHASE2_START, PHASE2_END) { local, alpha, scale ->
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            this.alpha = alpha
                            scaleX = scale
                            scaleY = scale
                        },
                ) {
                    drawPhase2Chicago(local, size.width, size.height, geo, sortedAreas)
                }
            }

            // Phase 3: Illinois (state)
            mapPhaseLayer(progress, PHASE3_START, PHASE3_END) { local, alpha, scale ->
                val il = illinois
                if (il != null) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                this.alpha = alpha
                                scaleX = scale
                                scaleY = scale
                            },
                    ) {
                        drawPhaseIllinois(local, size.width, size.height, il)
                    }
                }
            }

            // Phase 4: Continental US + green spread (country)
            mapPhaseLayer(progress, PHASE4_START, PHASE4_END) { local, alpha, scale ->
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            this.alpha = alpha
                            scaleX = scale
                            scaleY = scale
                        },
                ) {
                    drawPhase3US(local, size.width, size.height, geo, sortedStates)
                }
            }
        }

        // ── Lottie Globe layer (phases 5-6) ──
        val globeAlpha = when {
            progress < PHASE5_START -> 0f
            progress < PHASE5_START + 0.0625f -> // fade in over ~750ms
                ((progress - PHASE5_START) / 0.0625f).coerceIn(0f, 1f)
            else -> 1f
        }

        // Globe size transitions: larger during phase 5, shrinks for logo in phase 6
        val globeSizeDp = when {
            progress < PHASE6_START -> 280.dp
            else -> {
                val shrink = ((progress - PHASE6_START) / (1f - PHASE6_START)).coerceIn(0f, 1f)
                (280 - (shrink * 80).toInt()).dp // 280dp -> 200dp
            }
        }
        val globeOffsetY = when {
            progress < PHASE6_START -> (-20).dp
            else -> {
                val shift = ((progress - PHASE6_START) / (1f - PHASE6_START)).coerceIn(0f, 1f)
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

        // ── Phase labels ──
        // Phase 1 label: "North Lawndale" (neighborhood)
        if (progress < PHASE1_END && geoData != null) {
            val phase1Progress = progress / PHASE1_END
            val labelAlpha = when {
                phase1Progress < 0.2f -> phase1Progress / 0.2f
                phase1Progress > 0.9f -> (1f - phase1Progress) / 0.1f
                else -> 1f
            }.coerceIn(0f, 1f)

            Text(
                text = "North Lawndale",
                style = TextStyle(
                    color = Color.White.copy(alpha = labelAlpha),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp,
                ),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = (-60).dp),
            )
        }

        // Phase 2 label: "Chicago" (city)
        if (progress in PHASE2_START..PHASE2_END && geoData != null) {
            val phase2Progress = (progress - PHASE2_START) / (PHASE2_END - PHASE2_START)
            val labelAlpha = when {
                phase2Progress < 0.2f -> phase2Progress / 0.2f
                phase2Progress > 0.9f -> (1f - phase2Progress) / 0.1f
                else -> 1f
            }.coerceIn(0f, 1f)

            Text(
                text = "Chicago",
                style = TextStyle(
                    color = Color.White.copy(alpha = labelAlpha),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                ),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = 60.dp),
            )
        }

        // Phase 3 label: "Illinois" (state)
        if (progress in PHASE3_START..PHASE3_END && geoData != null) {
            val phase3Progress = (progress - PHASE3_START) / (PHASE3_END - PHASE3_START)
            val labelAlpha = when {
                phase3Progress < 0.2f -> phase3Progress / 0.2f
                phase3Progress > 0.9f -> (1f - phase3Progress) / 0.1f
                else -> 1f
            }.coerceIn(0f, 1f)

            Text(
                text = "Illinois",
                style = TextStyle(
                    color = Color.White.copy(alpha = labelAlpha),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                ),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = (-60).dp),
            )
        }

        // Phase 4 label: "United States" (country)
        if (progress in PHASE4_START..PHASE4_END && geoData != null) {
            val phase4Progress = (progress - PHASE4_START) / (PHASE4_END - PHASE4_START)
            val labelAlpha = when {
                phase4Progress < 0.3f -> phase4Progress / 0.3f
                phase4Progress > 0.9f -> (1f - phase4Progress) / 0.1f
                else -> 1f
            }.coerceIn(0f, 1f)

            Text(
                text = "United States",
                style = TextStyle(
                    color = Color.White.copy(alpha = labelAlpha),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.sp,
                ),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = (-60).dp),
            )
        }

        // ── Logo + Text overlay (phase 6) ──
        if (progress >= PHASE6_START) {
            val phase6Progress = ((progress - PHASE6_START) / (1f - PHASE6_START)).coerceIn(0f, 1f)
            val textAlpha = ((phase6Progress - 0.3f) / 0.5f).coerceIn(0f, 1f)

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
}

// ── F9 reduced-motion splash: static brand poster + logo, ~1.2s ──
@Composable
private fun ReducedMotionSplash(onFinished: () -> Unit) {
    val splashAlpha = remember { Animatable(1f) }
    var skipped by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(1200)
        if (!finished) {
            finished = true
            splashAlpha.animateTo(0f, tween(300))
            onFinished()
        }
    }

    // Same tap-to-skip affordance as the animated splash.
    LaunchedEffect(skipped) {
        if (skipped && !finished) {
            finished = true
            splashAlpha.animateTo(0f, tween(300))
            onFinished()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = splashAlpha.value }
            .pointerInput(Unit) {
                detectTapGestures { skipped = true }
            },
        contentAlignment = Alignment.Center,
    ) {
        // Static poster: brand background + soft glow, no motion.
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(SplashBackground)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        GlowGreen.copy(alpha = 0.15f),
                        GlowGreen.copy(alpha = 0.05f),
                        Color.Transparent,
                    ),
                    center = Offset(size.width / 2f, size.height * 0.40f),
                    radius = size.width * 0.6f,
                ),
                radius = size.width * 0.6f,
                center = Offset(size.width / 2f, size.height * 0.40f),
            )
        }

        GccLogo(
            size = 80.dp,
            showGlow = true,
            modifier = Modifier.offset(y = 50.dp),
        )

        Text(
            text = "Global Community Cleaners",
            style = TextStyle(
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            ),
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = 105.dp),
        )
    }
}

// ── Phase 1: North Lawndale Close-Up (real boundary) ──
private fun DrawScope.drawPhase1NorthLawndale(
    progress: Float,
    w: Float,
    h: Float,
    nl: SplashPolygon,
) {
    val center = Offset(w / 2f, h / 2f)
    val outerRing = nl.rings.firstOrNull() ?: return

    // Compute bounding box of NL polygon
    val minLat = outerRing.minOf { it.first }
    val maxLat = outerRing.maxOf { it.first }
    val minLng = outerRing.minOf { it.second }
    val maxLng = outerRing.maxOf { it.second }

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

    // Map polygon to fill ~70% screen width, centered
    val polyWidth = w * 0.70f
    val latRange = maxLat - minLat
    val lngRange = maxLng - minLng
    val aspect = latRange / lngRange
    val pxW = polyWidth
    val pxH = (pxW * aspect).toFloat()
    val offX = center.x - pxW / 2f
    val offY = center.y - pxH / 2f

    fun toCanvas(lat: Double, lng: Double): Offset {
        val x = offX + ((lng - minLng) / lngRange * pxW).toFloat()
        val y = offY + ((maxLat - lat) / latRange * pxH).toFloat()
        return Offset(x, y)
    }

    // Build path from real boundary
    val nlPath = Path()
    outerRing.forEachIndexed { i, (lat, lng) ->
        val pos = toCanvas(lat, lng)
        if (i == 0) nlPath.moveTo(pos.x, pos.y) else nlPath.lineTo(pos.x, pos.y)
    }
    nlPath.close()

    val bounds = nlPath.getBounds()

    // Grey base fill
    drawPath(nlPath, color = GreyBase)

    // Green sweep left-to-right with smoothstep easing
    val easedSweep = progress * progress * (3f - 2f * progress)
    val sweepX = bounds.left + bounds.width * easedSweep
    clipRect(left = bounds.left, top = bounds.top, right = sweepX, bottom = bounds.bottom) {
        drawPath(nlPath, color = GccGreen)
    }

    // Hotspot dots
    NL_HOTSPOTS.forEachIndexed { i, (lat, lng) ->
        val pos = toCanvas(lat, lng)
        val hotspotSweepNorm = ((pos.x - bounds.left) / bounds.width).coerceIn(0f, 1f)
        val cleaned = easedSweep > hotspotSweepNorm

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

    // White border + green glow stroke
    drawPath(nlPath, color = Color.White.copy(alpha = 0.25f), style = Stroke(width = 2f))
    drawPath(nlPath, color = GccGreen.copy(alpha = 0.1f), style = Stroke(width = 6f))
}

// ── Phase 2: Chicago Zoom-Out (all 77 real areas) ──
private fun DrawScope.drawPhase2Chicago(
    progress: Float,
    w: Float,
    h: Float,
    geo: SplashGeoData,
    sortedAreas: List<Pair<SplashPolygon, Double>>?,
) {
    val areas = sortedAreas ?: return
    val nlArea = geo.northLawndale ?: return

    // Compute bounding box of ALL Chicago areas
    val allPoints = geo.chicagoAreas.flatMap { area -> area.rings.firstOrNull() ?: emptyList() }
    val minLat = allPoints.minOf { it.first }
    val maxLat = allPoints.maxOf { it.first }
    val minLng = allPoints.minOf { it.second }
    val maxLng = allPoints.maxOf { it.second }
    val latRange = maxLat - minLat
    val lngRange = maxLng - minLng

    // Map to 85% screen height, centered, maintaining aspect ratio
    val mapH = h * 0.85f
    val mapW = (mapH * (lngRange / latRange)).toFloat()
    val actualMapW = if (mapW > w * 0.95f) w * 0.95f else mapW
    val actualMapH = if (mapW > w * 0.95f) (actualMapW * (latRange / lngRange)).toFloat() else mapH
    val mapOffX = (w - actualMapW) / 2f
    val mapOffY = (h - actualMapH) / 2f

    fun toCanvas(lat: Double, lng: Double): Offset {
        val x = mapOffX + ((lng - minLng) / lngRange * actualMapW).toFloat()
        val y = mapOffY + ((maxLat - lat) / latRange * actualMapH).toFloat()
        return Offset(x, y)
    }

    fun buildAreaPath(ring: List<Pair<Double, Double>>): Path {
        val path = Path()
        ring.forEachIndexed { idx, (lat, lng) ->
            val pos = toCanvas(lat, lng)
            if (idx == 0) path.moveTo(pos.x, pos.y) else path.lineTo(pos.x, pos.y)
        }
        path.close()
        return path
    }

    val maxDist = areas.maxOfOrNull { it.second }?.toFloat() ?: 1f

    // Draw surrounding areas (sorted by distance from NL)
    areas.forEach { (area, dist) ->
        val outerRing = area.rings.firstOrNull() ?: return@forEach
        val areaPath = buildAreaPath(outerRing)
        val distNorm = (dist.toFloat() / maxDist).coerceIn(0f, 1f)
        val cleaned = progress > distNorm

        val fillColor = if (cleaned) {
            lerp(GreyBase, GccGreen, ((progress - distNorm) / 0.15f).coerceIn(0f, 1f))
        } else {
            GreyBase
        }
        drawPath(areaPath, color = fillColor)
        drawPath(areaPath, color = Color.White.copy(alpha = 0.15f), style = Stroke(width = 1f))

        // Hotspot dot at centroid
        val centLat = outerRing.map { it.first }.average()
        val centLng = outerRing.map { it.second }.average()
        val centPos = toCanvas(centLat, centLng)
        val dotRadius = w * 0.005f
        val pulsePhase = sin(progress * PI.toFloat() * 4f + dist.toFloat() * 10f)

        val dotColor = if (cleaned) {
            lerp(HotspotRed, GccGreen, ((progress - distNorm) / 0.15f).coerceIn(0f, 1f))
        } else {
            HotspotRed
        }
        val r = if (cleaned) dotRadius * 0.6f else dotRadius + dotRadius * 0.25f * pulsePhase
        drawCircle(color = dotColor, radius = r, center = centPos)
    }

    // North Lawndale always on top, always green
    val nlRing = nlArea.rings.firstOrNull() ?: return
    val nlPath = buildAreaPath(nlRing)
    drawPath(nlPath, color = GccGreen)
    drawPath(nlPath, color = GccGreenDark, style = Stroke(width = 2f))
}

// ── Phase 3: Continental US with green spread from Chicago ──
private fun DrawScope.drawPhase3US(
    progress: Float,
    w: Float,
    h: Float,
    geo: SplashGeoData,
    sortedStates: List<Pair<SplashPolygon, Double>>?,
) {
    val states = sortedStates ?: return
    val latRange = US_MAX_LAT - US_MIN_LAT
    val lngRange = US_MAX_LNG - US_MIN_LNG

    // Map to 90% screen width, maintaining aspect ratio
    val mapW = w * 0.90f
    val mapH = (mapW * (latRange / lngRange)).toFloat()
    val mapOffX = (w - mapW) / 2f
    val mapOffY = (h - mapH) / 2f

    fun toCanvas(lat: Double, lng: Double): Offset {
        val x = mapOffX + ((lng - US_MIN_LNG) / lngRange * mapW).toFloat()
        val y = mapOffY + ((US_MAX_LAT - lat) / latRange * mapH).toFloat()
        return Offset(x, y)
    }

    // Overall alpha: fade out in last 15% of phase for smooth transition to globe
    val stateAlpha = when {
        progress > 0.85f -> (1f - progress) / 0.15f
        else -> 1f
    }.coerceIn(0f, 1f)

    // Green spread starts at 15% of phase (after states are visible) and fills by 85%
    val spreadProgress = ((progress - 0.15f) / 0.70f).coerceIn(0f, 1f)
    // Smoothstep easing for organic spread feel
    val easedSpread = spreadProgress * spreadProgress * (3f - 2f * spreadProgress)

    val maxDist = states.maxOfOrNull { it.second }?.toFloat() ?: 1f

    // Draw each state with fill + borders
    states.forEach { (state, dist) ->
        val distNorm = (dist.toFloat() / maxDist).coerceIn(0f, 1f)
        val cleaned = easedSpread > distNorm

        state.rings.forEach { ring ->
            if (ring.size < 3) return@forEach
            val path = Path()
            ring.forEachIndexed { idx, (lat, lng) ->
                val pos = toCanvas(lat, lng)
                if (idx == 0) path.moveTo(pos.x, pos.y) else path.lineTo(pos.x, pos.y)
            }
            path.close()

            // Fill: transition from dark to green as the wave reaches each state
            val fillColor = if (cleaned) {
                val cleanAmount = ((easedSpread - distNorm) / 0.12f).coerceIn(0f, 1f)
                lerp(Color(0xFF1A2A1A), GccGreen.copy(alpha = 0.7f), cleanAmount)
            } else {
                Color(0xFF1A2A1A).copy(alpha = 0.4f * stateAlpha)
            }
            drawPath(path, color = fillColor.copy(alpha = fillColor.alpha * stateAlpha))

            // State border strokes
            drawPath(
                path,
                color = Color.White.copy(alpha = 0.2f * stateAlpha),
                style = Stroke(width = 1f),
            )
        }
    }

    // Green pulsing glow dot at Chicago's position (always visible)
    val chicagoPos = toCanvas(CHICAGO_LAT, CHICAGO_LNG)
    val pulse = sin(progress * PI.toFloat() * 4f)
    val glowRadius = w * 0.03f + w * 0.01f * pulse

    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                GccGreen.copy(alpha = 0.6f * stateAlpha),
                GccGreen.copy(alpha = 0.2f * stateAlpha),
                Color.Transparent,
            ),
            center = chicagoPos,
            radius = glowRadius * 3f,
        ),
        radius = glowRadius * 3f,
        center = chicagoPos,
    )
    drawCircle(
        color = GccGreen.copy(alpha = 0.9f * stateAlpha),
        radius = glowRadius * 0.4f,
        center = chicagoPos,
    )
}

// ── Map phase layer compositing ──
// Emits [content] only while [progress] is inside [start, end], with a
// PHASE_FADE crossfade at both edges and an ease-out scale settle
// (1.10x -> 1.0x) across the window — the settle is what chains the four
// maps into one continuous city -> state -> country -> world pull-back.
@Composable
private fun mapPhaseLayer(
    progress: Float,
    start: Float,
    end: Float,
    content: @Composable (local: Float, alpha: Float, scale: Float) -> Unit,
) {
    if (progress < start || progress > end) return
    val alpha = minOf(
        (progress - start) / PHASE_FADE,
        (end - progress) / PHASE_FADE,
        1f,
    ).coerceIn(0f, 1f)
    val local = ((progress - start) / (end - start)).coerceIn(0f, 1f)
    val settle = 1f - (1f - local).let { it * it * it } // ease-out cubic
    content(local, alpha, 1.10f - 0.10f * settle)
}

// ── Phase 3: Illinois (state) — sweep-to-green + Chicago origin dot ──
private fun DrawScope.drawPhaseIllinois(
    progress: Float,
    w: Float,
    h: Float,
    illinois: SplashPolygon,
) {
    val rings = illinois.rings
    if (rings.isEmpty()) return

    // Bounding box across every ring (Illinois is one landmass, but stay general)
    val allPoints = rings.flatten()
    val minLat = allPoints.minOf { it.first }
    val maxLat = allPoints.maxOf { it.first }
    val minLng = allPoints.minOf { it.second }
    val maxLng = allPoints.maxOf { it.second }
    val latRange = maxLat - minLat
    val lngRange = maxLng - minLng

    // Map to ~65% screen height, centered, maintaining aspect ratio
    val mapH = h * 0.65f
    val mapW = (mapH * (lngRange / latRange)).toFloat()
    val actualMapW = if (mapW > w * 0.85f) w * 0.85f else mapW
    val actualMapH = if (mapW > w * 0.85f) (actualMapW * (latRange / lngRange)).toFloat() else mapH
    val offX = (w - actualMapW) / 2f
    val offY = (h - actualMapH) / 2f

    fun toCanvas(lat: Double, lng: Double): Offset {
        val x = offX + ((lng - minLng) / lngRange * actualMapW).toFloat()
        val y = offY + ((maxLat - lat) / latRange * actualMapH).toFloat()
        return Offset(x, y)
    }

    fun buildPath(ring: List<Pair<Double, Double>>): Path {
        val path = Path()
        ring.forEachIndexed { i, (lat, lng) ->
            val pos = toCanvas(lat, lng)
            if (i == 0) path.moveTo(pos.x, pos.y) else path.lineTo(pos.x, pos.y)
        }
        path.close()
        return path
    }

    val statePath = Path()
    rings.forEach { statePath.addPath(buildPath(it)) }
    val bounds = statePath.getBounds()

    // Subtle radial glow behind the state
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                GlowGreen.copy(alpha = 0.10f * progress),
                Color.Transparent,
            ),
            center = Offset(w / 2f, h / 2f),
            radius = w * 0.5f,
        ),
        radius = w * 0.5f,
        center = Offset(w / 2f, h / 2f),
    )

    // Grey base fill
    drawPath(statePath, color = GreyBase)

    // Green sweep radiating out from Chicago's longitude (smoothstep eased) —
    // the same "cleaning spreads outward" language as phases 1/2/4.
    val easedSweep = progress * progress * (3f - 2f * progress)
    val chicagoPos = toCanvas(CHICAGO_LAT, CHICAGO_LNG)
    val maxSpread = maxOf(bounds.right - chicagoPos.x, chicagoPos.x - bounds.left)
        .coerceAtLeast(1f)
    val spreadX = maxSpread * easedSweep
    clipRect(
        left = chicagoPos.x - spreadX,
        top = bounds.top,
        right = chicagoPos.x + spreadX,
        bottom = bounds.bottom,
    ) {
        drawPath(statePath, color = GccGreen)
    }

    // Chicago origin dot: red pulse until the sweep reaches it, then green
    val dotRadius = w * 0.012f
    val cleaned = easedSweep > 0.02f
    val pulsePhase = sin(progress * PI.toFloat() * 6f)
    if (!cleaned) {
        drawCircle(
            color = HotspotRed.copy(alpha = 0.25f + 0.1f * pulsePhase),
            radius = dotRadius * 2.2f,
            center = chicagoPos,
        )
    }
    drawCircle(
        color = if (cleaned) lerp(HotspotRed, GccGreen, (easedSweep / 0.15f).coerceIn(0f, 1f)) else HotspotRed,
        radius = if (cleaned) dotRadius * 0.8f else dotRadius * (1f + 0.25f * pulsePhase),
        center = chicagoPos,
    )

    // White border + green glow stroke
    drawPath(statePath, color = Color.White.copy(alpha = 0.25f), style = Stroke(width = 2f))
    drawPath(statePath, color = GccGreen.copy(alpha = 0.1f), style = Stroke(width = 6f))
}

// ── Transition particles (green dots floating outward) ──
private fun DrawScope.drawTransitionParticles(progress: Float, w: Float, h: Float) {
    val center = Offset(w / 2f, h * 0.40f)
    val particleCount = 12
    for (i in 0 until particleCount) {
        val angle = (2f * PI.toFloat() / particleCount) * i + progress * PI.toFloat() * 0.5f
        val baseRadius = w * 0.2f
        val expandRadius = baseRadius + progress * w * 0.25f
        val x = center.x + cos(angle) * expandRadius
        val y = center.y + sin(angle) * expandRadius
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
