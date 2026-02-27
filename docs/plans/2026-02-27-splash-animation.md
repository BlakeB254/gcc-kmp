# Splash Animation Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a cinematic 8-second skippable splash animation that shows cleaning spreading from North Lawndale outward, morphing into a globe, then revealing the GCC logo.

**Architecture:** Single new `SplashScreen.kt` composable using Compose `Canvas` with `Animatable` progress values for 4 timed phases. `App.kt` gates on a `showSplash` boolean before showing Decompose navigation. No new dependencies.

**Tech Stack:** Compose Canvas, Animatable, tween, spring, existing CommunityArea polygon data, existing GccLogo composable.

---

### Task 1: Create SplashScreen skeleton with skip and auto-dismiss

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SplashScreen.kt`

**Step 1: Create the SplashScreen composable**

```kotlin
package com.gcc.app.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.components.GccLogo
import com.gcc.app.ui.map.chicagoCommunityAreas
import com.gcc.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.*

// Duration constants (Fibonacci-adjacent)
private const val PHASE1_END = 2000L    // Close-up cleaning
private const val PHASE2_END = 4500L    // Zoom-out
private const val PHASE3_END = 6500L    // Globe morph
private const val PHASE4_END = 8000L    // Logo + exit
private const val SKIP_FADE_MS = 300

// Colors
private val SplashBg = Color(0xFF0F4C2A)
private val AreaGrey = Color(0xFF4A4A4A)
private val HotspotRed = Color(0xFFE53935)
private val OceanBlue = Color(0xFF1565C0)
private val CleanGreen = GccGreen

@Composable
fun SplashScreen(
    onFinished: () -> Unit,
) {
    // Master progress: 0f..1f over PHASE4_END ms
    val masterProgress = remember { Animatable(0f) }
    // Fade alpha for skip/exit
    val splashAlpha = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    // Auto-advance master progress
    LaunchedEffect(Unit) {
        masterProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = PHASE4_END.toInt(),
                easing = LinearEasing,
            ),
        )
        // Auto-dismiss after animation completes
        splashAlpha.animateTo(0f, tween(500))
        onFinished()
    }

    // Skip handler
    val skip: () -> Unit = remember {
        {
            scope.launch {
                masterProgress.stop()
                splashAlpha.animateTo(0f, tween(SKIP_FADE_MS))
                onFinished()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = splashAlpha.value }
            .background(SplashBg)
            .pointerInput(Unit) { detectTapGestures { skip() } },
        contentAlignment = Alignment.Center,
    ) {
        val progress = masterProgress.value

        Canvas(modifier = Modifier.fillMaxSize()) {
            drawSplashAnimation(progress)
        }

        // Logo overlay (Phase 4 only)
        val logoProgress = ((progress - 0.8125f) / 0.1875f).coerceIn(0f, 1f) // 6.5s/8s to 8s/8s
        if (logoProgress > 0f) {
            GccLogo(
                size = 80.dp,
                showGlow = true,
                modifier = Modifier.graphicsLayer {
                    val springScale = if (logoProgress < 0.5f) {
                        // Overshoot spring approximation
                        1.2f * (logoProgress / 0.5f)
                    } else {
                        1.2f - 0.2f * ((logoProgress - 0.5f) / 0.5f)
                    }
                    scaleX = springScale
                    scaleY = springScale
                    alpha = logoProgress.coerceAtMost(1f)
                },
            )
        }
    }
}
```

This is the skeleton — the `drawSplashAnimation` function will be built in Tasks 2-5.

**Step 2: Add stub draw function**

At the bottom of `SplashScreen.kt`, add:

```kotlin
// ── Drawing Functions ──

private fun DrawScope.drawSplashAnimation(progress: Float) {
    // Phase boundaries as fractions of total duration
    val p1 = (progress / 0.25f).coerceIn(0f, 1f)          // 0-2s -> 0-1
    val p2 = ((progress - 0.25f) / 0.3125f).coerceIn(0f, 1f) // 2-4.5s -> 0-1
    val p3 = ((progress - 0.5625f) / 0.25f).coerceIn(0f, 1f) // 4.5-6.5s -> 0-1
    val p4 = ((progress - 0.8125f) / 0.1875f).coerceIn(0f, 1f) // 6.5-8s -> 0-1

    drawPhase1CloseUp(p1)
    if (p2 > 0f) drawPhase2ZoomOut(p2, p1)
    if (p3 > 0f) drawPhase3GlobeMorph(p3)
    if (p4 > 0f) drawPhase4LogoReveal(p4)
}

private fun DrawScope.drawPhase1CloseUp(progress: Float) {
    // TODO: Task 2
}

private fun DrawScope.drawPhase2ZoomOut(progress: Float, phase1: Float) {
    // TODO: Task 3
}

private fun DrawScope.drawPhase3GlobeMorph(progress: Float) {
    // TODO: Task 4
}

private fun DrawScope.drawPhase4LogoReveal(progress: Float) {
    // TODO: Task 5
}
```

**Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SplashScreen.kt
git commit -m "feat(splash): add SplashScreen skeleton with skip and auto-dismiss"
```

---

### Task 2: Implement Phase 1 — North Lawndale close-up with cleaning wave

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SplashScreen.kt`

**Step 1: Add polygon and hotspot data at the top of the file (below color constants)**

```kotlin
// North Lawndale polygon (lat/lng from CommunityAreaData, area #29)
private val northLawndalePolygon = listOf(
    41.8750 to -87.7410,
    41.8750 to -87.6920,
    41.8600 to -87.6920,
    41.8440 to -87.6950,
    41.8440 to -87.7350,
    41.8550 to -87.7410,
)

// Hotspot positions within North Lawndale (normalized 0-1 within polygon bounds)
private val hotspots = listOf(
    0.3f to 0.25f,
    0.7f to 0.4f,
    0.5f to 0.7f,
    0.2f to 0.6f,
)
```

**Step 2: Implement drawPhase1CloseUp**

```kotlin
private fun DrawScope.drawPhase1CloseUp(progress: Float) {
    val cx = size.width / 2
    val cy = size.height / 2
    val polyW = size.width * 0.7f
    val polyH = size.height * 0.35f
    val left = cx - polyW / 2
    val top = cy - polyH / 2

    // Convert lat/lng polygon to screen coordinates
    val minLat = northLawndalePolygon.minOf { it.first }
    val maxLat = northLawndalePolygon.maxOf { it.first }
    val minLng = northLawndalePolygon.minOf { it.second }
    val maxLng = northLawndalePolygon.maxOf { it.second }

    fun toScreen(lat: Double, lng: Double): Offset {
        val x = left + ((lng - minLng) / (maxLng - minLng)).toFloat() * polyW
        val y = top + ((maxLat - lat) / (maxLat - minLat)).toFloat() * polyH
        return Offset(x, y)
    }

    // Build polygon path
    val path = Path().apply {
        val first = toScreen(northLawndalePolygon[0].first, northLawndalePolygon[0].second)
        moveTo(first.x, first.y)
        for (i in 1 until northLawndalePolygon.size) {
            val pt = toScreen(northLawndalePolygon[i].first, northLawndalePolygon[i].second)
            lineTo(pt.x, pt.y)
        }
        close()
    }

    // Grey base fill
    drawPath(path, color = AreaGrey)

    // Green cleaning wave (clip left-to-right sweep)
    val sweepX = left + polyW * progress
    clipRect(left = 0f, top = 0f, right = sweepX, bottom = size.height) {
        drawPath(path, color = CleanGreen)
    }

    // Polygon outline
    drawPath(path, color = CleanGreen.copy(alpha = 0.6f), style = Stroke(2f))

    // Red hotspots — pulse then shrink as wave passes
    hotspots.forEach { (nx, ny) ->
        val hx = left + nx * polyW
        val hy = top + ny * polyH
        val hotspotProgress = ((sweepX - hx) / (polyW * 0.15f)).coerceIn(0f, 1f)

        if (hotspotProgress < 1f) {
            // Pulse animation (sin wave on radius)
            val pulse = 1f + 0.3f * sin(progress * 8f * PI.toFloat())
            val baseRadius = size.width * 0.015f
            val radius = baseRadius * pulse * (1f - hotspotProgress)
            val color = lerp(HotspotRed, CleanGreen, hotspotProgress)
            drawCircle(color = color, radius = radius, center = Offset(hx, hy))
        }
    }
}
```

**Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SplashScreen.kt
git commit -m "feat(splash): implement Phase 1 — North Lawndale close-up cleaning wave"
```

---

### Task 3: Implement Phase 2 — Zoom out with radial green wave

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SplashScreen.kt`

**Step 1: Add nearby community area data**

At the top, below the hotspot data, add a helper that computes distance-sorted areas:

```kotlin
// Pre-compute community areas sorted by distance from North Lawndale centroid
private val nlCenterLat = 41.8595
private val nlCenterLng = -87.7165

private val nearbyCommunityAreas by lazy {
    chicagoCommunityAreas
        .filter { it.polygon.isNotEmpty() && it.name != "north_lawndale" }
        .map { area ->
            val centroid = area.polygon.let { poly ->
                poly.sumOf { it.first } / poly.size to poly.sumOf { it.second } / poly.size
            }
            val dist = sqrt(
                (centroid.first - nlCenterLat).pow(2) +
                (centroid.second - nlCenterLng).pow(2)
            )
            area to dist
        }
        .sortedBy { it.second }
        .take(24) // nearest 24 community areas
}
```

**Step 2: Implement drawPhase2ZoomOut**

```kotlin
private fun DrawScope.drawPhase2ZoomOut(progress: Float, phase1: Float) {
    val cx = size.width / 2
    val cy = size.height / 2

    // Zoom scale: starts at 1.0 (close-up), ends at 0.15 (overview)
    val zoomScale = 1f - progress * 0.85f

    // Compute global lat/lng to screen mapping for all areas
    val allAreas = nearbyCommunityAreas
    val allPolygons = allAreas.map { it.first.polygon } + listOf(northLawndalePolygon)
    val allPoints = allPolygons.flatten()
    val minLat = allPoints.minOf { it.first }
    val maxLat = allPoints.maxOf { it.first }
    val minLng = allPoints.minOf { it.second }
    val maxLng = allPoints.maxOf { it.second }

    val mapW = size.width * 3f // full map extent at zoom=1
    val mapH = size.height * 2f

    fun toScreen(lat: Double, lng: Double): Offset {
        val baseX = ((lng - minLng) / (maxLng - minLng)).toFloat() * mapW - mapW / 2
        val baseY = ((maxLat - lat) / (maxLat - minLat)).toFloat() * mapH - mapH / 2
        return Offset(cx + baseX * zoomScale, cy + baseY * zoomScale)
    }

    // Max radial distance for wave timing
    val maxDist = allAreas.lastOrNull()?.second ?: 1.0

    // Draw each community area
    allAreas.forEach { (area, dist) ->
        val path = Path().apply {
            val pts = area.polygon
            if (pts.isEmpty()) return@forEach
            val first = toScreen(pts[0].first, pts[0].second)
            moveTo(first.x, first.y)
            for (i in 1 until pts.size) {
                val pt = toScreen(pts[i].first, pts[i].second)
                lineTo(pt.x, pt.y)
            }
            close()
        }

        // Appear as zoom reveals them
        val appearProgress = (progress * 1.5f - (dist / maxDist).toFloat() * 0.5f).coerceIn(0f, 1f)
        if (appearProgress <= 0f) return@forEach

        // Color: grey -> green based on radial wave from NL
        val cleanWave = (progress - (dist / maxDist).toFloat() * 0.6f).coerceIn(0f, 1f) / 0.4f
        val areaColor = lerp(AreaGrey, CleanGreen, cleanWave.coerceIn(0f, 1f))

        drawPath(path, color = areaColor.copy(alpha = appearProgress))
        drawPath(path, color = CleanGreen.copy(alpha = 0.4f * appearProgress), style = Stroke(1f))

        // Red hotspots per area (1-2 based on area number)
        if (cleanWave < 0.8f) {
            val hx = area.polygon.sumOf { it.second }.toFloat() / area.polygon.size
            val hy = area.polygon.sumOf { it.first }.toFloat() / area.polygon.size
            val hotCenter = toScreen(hy.toDouble(), hx.toDouble())
            val pulse = 1f + 0.2f * sin(progress * 6f * PI.toFloat() + dist.toFloat())
            val r = size.width * 0.008f * pulse * (1f - cleanWave / 0.8f) * zoomScale
            val hotColor = lerp(HotspotRed, CleanGreen, (cleanWave / 0.8f).coerceIn(0f, 1f))
            drawCircle(color = hotColor.copy(alpha = appearProgress), radius = r, center = hotCenter)
        }
    }

    // Re-draw North Lawndale (always green, on top)
    val nlPath = Path().apply {
        val first = toScreen(northLawndalePolygon[0].first, northLawndalePolygon[0].second)
        moveTo(first.x, first.y)
        for (i in 1 until northLawndalePolygon.size) {
            val pt = toScreen(northLawndalePolygon[i].first, northLawndalePolygon[i].second)
            lineTo(pt.x, pt.y)
        }
        close()
    }
    drawPath(nlPath, color = CleanGreen)
    drawPath(nlPath, color = CleanGreen.copy(alpha = 0.6f), style = Stroke(1.5f))
}
```

**Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SplashScreen.kt
git commit -m "feat(splash): implement Phase 2 — zoom out with radial cleaning wave"
```

---

### Task 4: Implement Phase 3 — Globe morph with continent outlines

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SplashScreen.kt`

**Step 1: Add simplified continent outline data**

Below the `nearbyCommunityAreas` lazy val, add continent paths as normalized coordinates (0-1 range, will be mapped to globe circle):

```kotlin
// Simplified continent outlines — normalized X/Y in orthographic projection
// Each continent is a list of (x, y) pairs where (0.5, 0.5) is globe center
private val continentPaths: List<List<Pair<Float, Float>>> = listOf(
    // North America
    listOf(
        0.18f to 0.18f, 0.28f to 0.15f, 0.35f to 0.22f, 0.38f to 0.30f,
        0.35f to 0.38f, 0.30f to 0.42f, 0.25f to 0.38f, 0.20f to 0.35f,
        0.15f to 0.28f, 0.18f to 0.18f,
    ),
    // South America
    listOf(
        0.30f to 0.48f, 0.35f to 0.50f, 0.38f to 0.58f, 0.36f to 0.68f,
        0.32f to 0.78f, 0.28f to 0.82f, 0.26f to 0.75f, 0.25f to 0.65f,
        0.27f to 0.55f, 0.30f to 0.48f,
    ),
    // Europe
    listOf(
        0.47f to 0.18f, 0.52f to 0.15f, 0.55f to 0.18f, 0.54f to 0.25f,
        0.50f to 0.30f, 0.47f to 0.28f, 0.45f to 0.22f, 0.47f to 0.18f,
    ),
    // Africa
    listOf(
        0.47f to 0.32f, 0.53f to 0.30f, 0.58f to 0.38f, 0.57f to 0.50f,
        0.54f to 0.62f, 0.50f to 0.68f, 0.46f to 0.60f, 0.44f to 0.48f,
        0.45f to 0.38f, 0.47f to 0.32f,
    ),
    // Asia
    listOf(
        0.55f to 0.12f, 0.65f to 0.10f, 0.78f to 0.15f, 0.82f to 0.25f,
        0.80f to 0.35f, 0.72f to 0.42f, 0.65f to 0.38f, 0.58f to 0.32f,
        0.55f to 0.25f, 0.55f to 0.12f,
    ),
    // Australia
    listOf(
        0.72f to 0.58f, 0.80f to 0.55f, 0.85f to 0.60f, 0.83f to 0.68f,
        0.78f to 0.72f, 0.72f to 0.68f, 0.70f to 0.62f, 0.72f to 0.58f,
    ),
)
```

**Step 2: Implement drawPhase3GlobeMorph**

```kotlin
private fun DrawScope.drawPhase3GlobeMorph(progress: Float) {
    val cx = size.width / 2
    val cy = size.height * 0.42f // slightly above center for logo space below
    val globeRadius = size.width * 0.35f

    // Circular clip — shrinks the map view into a globe
    drawCircle(
        color = OceanBlue.copy(alpha = progress * 0.8f),
        radius = globeRadius,
        center = Offset(cx, cy),
    )

    // Draw continents with staggered fade-in
    continentPaths.forEachIndexed { index, continent ->
        val stagger = (progress - index * 0.08f).coerceIn(0f, 1f) / 0.6f
        val alpha = stagger.coerceIn(0f, 1f)
        if (alpha <= 0f) return@forEachIndexed

        val path = Path().apply {
            val first = continent.first()
            moveTo(
                cx - globeRadius + first.first * globeRadius * 2,
                cy - globeRadius + first.second * globeRadius * 2,
            )
            for (i in 1 until continent.size) {
                val pt = continent[i]
                lineTo(
                    cx - globeRadius + pt.first * globeRadius * 2,
                    cy - globeRadius + pt.second * globeRadius * 2,
                )
            }
            close()
        }

        // Clip to globe circle
        clipPath(
            Path().apply { addOval(androidx.compose.ui.geometry.Rect(
                cx - globeRadius, cy - globeRadius,
                cx + globeRadius, cy + globeRadius,
            )) }
        ) {
            drawPath(path, color = CleanGreen.copy(alpha = alpha))
            drawPath(path, color = CleanGreen.copy(alpha = alpha * 0.5f), style = Stroke(1.5f))
        }
    }

    // Globe outline ring
    drawCircle(
        color = CleanGreen.copy(alpha = 0.3f * progress),
        radius = globeRadius,
        center = Offset(cx, cy),
        style = Stroke(2f),
    )

    // Subtle highlight arc (top-left shine)
    drawArc(
        color = Color.White.copy(alpha = 0.1f * progress),
        startAngle = 200f,
        sweepAngle = 80f,
        useCenter = false,
        topLeft = Offset(cx - globeRadius, cy - globeRadius),
        size = Size(globeRadius * 2, globeRadius * 2),
        style = Stroke(3f),
    )
}
```

**Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SplashScreen.kt
git commit -m "feat(splash): implement Phase 3 — globe morph with continent outlines"
```

---

### Task 5: Implement Phase 4 — Globe rotation and logo reveal

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SplashScreen.kt`

**Step 1: Implement drawPhase4LogoReveal**

```kotlin
private fun DrawScope.drawPhase4LogoReveal(progress: Float) {
    val cx = size.width / 2
    val cy = size.height * 0.35f // globe moves up to make room for logo
    val globeRadius = size.width * 0.25f // slightly smaller

    // Ocean base
    drawCircle(color = OceanBlue.copy(alpha = 0.8f), radius = globeRadius, center = Offset(cx, cy))

    // Rotating continents — shift X based on progress to simulate spin
    val rotationOffset = progress * globeRadius * 0.4f

    continentPaths.forEachIndexed { _, continent ->
        val path = Path().apply {
            val first = continent.first()
            moveTo(
                cx - globeRadius + first.first * globeRadius * 2 + rotationOffset,
                cy - globeRadius + first.second * globeRadius * 2,
            )
            for (i in 1 until continent.size) {
                val pt = continent[i]
                lineTo(
                    cx - globeRadius + pt.first * globeRadius * 2 + rotationOffset,
                    cy - globeRadius + pt.second * globeRadius * 2,
                )
            }
            close()
        }

        clipPath(
            Path().apply { addOval(androidx.compose.ui.geometry.Rect(
                cx - globeRadius, cy - globeRadius,
                cx + globeRadius, cy + globeRadius,
            )) }
        ) {
            drawPath(path, color = CleanGreen)
            drawPath(path, color = CleanGreen.copy(alpha = 0.4f), style = Stroke(1f))
        }
    }

    // Globe outline
    drawCircle(
        color = CleanGreen.copy(alpha = 0.3f),
        radius = globeRadius,
        center = Offset(cx, cy),
        style = Stroke(2f),
    )
}
```

The GCC logo and text overlay are already handled in the `SplashScreen` composable (Task 1) via the `logoProgress` block using `GccLogo` composable with spring scale effect.

**Step 2: Add "Global Community Cleaners" text below logo**

In the `SplashScreen` composable, update the logo overlay section to also show text:

```kotlin
// In the Box content, after the GccLogo block:
if (logoProgress > 0.3f) {
    val textAlpha = ((logoProgress - 0.3f) / 0.7f).coerceIn(0f, 1f)
    androidx.compose.material3.Text(
        "Global Community Cleaners",
        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
        color = Color.White.copy(alpha = textAlpha * 0.9f),
        modifier = Modifier
            .align(Alignment.Center)
            .offset(y = 60.dp),
    )
}
```

**Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SplashScreen.kt
git commit -m "feat(splash): implement Phase 4 — globe rotation and logo reveal"
```

---

### Task 6: Wire SplashScreen into App.kt

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/App.kt`

**Step 1: Add splash gate to App composable**

Replace the existing `App` function:

```kotlin
@Composable
fun App(component: RootComponent) {
    var showSplash by remember { mutableStateOf(true) }

    GccTheme {
        if (showSplash) {
            SplashScreen(onFinished = { showSplash = false })
        } else {
            Children(
                stack = component.childStack,
                animation = stackAnimation(fade() + scale()),
            ) { child ->
                // ... existing when block unchanged ...
            }
        }
    }
}
```

Add the necessary imports at the top:

```kotlin
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
```

**Step 2: Build and verify on iOS simulator**

```bash
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator \
  -destination 'id=7A800B91-D9FD-4AC3-BC6B-3850739B6385' -configuration Debug build
xcrun simctl install 7A800B91-D9FD-4AC3-BC6B-3850739B6385 \
  "$HOME/Library/Developer/Xcode/DerivedData/iosApp-aszhbvdlckdbveadzchqhnnzcllm/Build/Products/Debug-iphonesimulator/Global Community Cleaners.app"
xcrun simctl launch 7A800B91-D9FD-4AC3-BC6B-3850739B6385 com.globalcommunitycleaners.worker
```

Take a screenshot at ~2s, ~5s, and ~8s to verify all 4 phases render. Verify tap-to-skip works.

**Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/App.kt \
       composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SplashScreen.kt
git commit -m "feat(splash): wire SplashScreen into App.kt with skip-to-dismiss"
```

---

### Task 7: Visual polish and timing tuning

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SplashScreen.kt`

**Step 1: Visual polish pass**

After seeing the animation running on a real device:

- Adjust phase transition smoothness (add easing curves between phases if jumpy)
- Tune hotspot pulse frequency and size
- Adjust continent shapes if they look off
- Ensure Phase 2→3 transition is smooth (community areas alpha-fade as globe circle clips in)
- Add a `drawPhase2to3Crossfade` helper if needed: community area alpha = `1f - p3`, globe elements alpha = `p3`
- Verify the logo spring bounce looks good (adjust DampingRatio if needed)

**Step 2: Build for Android and verify**

```bash
./gradlew :composeApp:assembleDebug
adb install -r composeApp/build/outputs/apk/debug/composeApp-debug.apk
adb shell am start -n com.gcc.app/.MainActivity
```

**Step 3: Final commit**

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SplashScreen.kt
git commit -m "feat(splash): visual polish and timing tuning"
```

**Step 4: Push**

```bash
git push
```
