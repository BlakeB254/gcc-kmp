# Scan Visibility Rework Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax. Each task is a TDD cycle: failing test → run (FAIL) → minimal impl → run (PASS) → commit. Read a file before editing — line numbers drift. Implements Workstreams 2–6 of `docs/superpowers/specs/2026-07-08-scan-visibility-and-indoor-footprint-design.md`. Workstream 1 (indoor footprints) is a SEPARATE plan — do NOT touch `BuildingData.kt`, `building_footprints`, or `/api/map/buildings` here.

**Goal:** Finish + fix + declutter the env-scan visibility model: quality-gate the deep (CLOSE) tier; render a scene-aware "flashlight" beam on the minimap from the ray-fan envelope; route ALL map coverage through the merged organic-outline engine so scanned areas "paint" instead of tiling; remove the neon AR coverage quads bleeding into the camera; declutter the env-scan screen.

**Architecture:** Crediting in `CoverageTracker` (commonMain, pure, StateFlow). Per-tick raycast hits from `CoverageRayFan.cast()` (iosMain) via `arOverlay.setCoverageHitListener` / `RouteScanViewModel.onCoverageRayHits`. Quality fuses in `ScanQualityScorer` (commonMain). Minimap = Compose Canvas over a fixed-zoom `GccMap`; main map = MapLibre layers from GeoJSON (`GeoJsonLayers.buildCoverageStateGeoJsons` → `GccMap.ios.kt`). Organic outlines from `CoverageOutline.quantizeAndTraceOutlines`. AR camera = `ArEnvScan.kt` (SceneKit/K-N) → RealityKit `GccArNative.swift`.

**Tech Stack:** Kotlin Multiplatform (common + iosMain), Compose Multiplatform, maplibre-compose 0.12.1, SceneKit/RealityKit/ARKit via K/N cinterop + Swift native module, `kotlin.test` in `composeApp` commonTest.

## Global Constraints

- **MapLibre Canvas overlays MUST bias zoom by `mapOverlayZoomBias(density)=log2(density*2)`** — new minimap geometry projects with `cameraZoom + mapOverlayZoomBias(density)` via `metersToPixels`/`toOffset`.
- **z-order = declaration order** (maplibre-compose 0.12; first = bottom). Don't reorder existing layers; don't allocate sources/layers for empty data.
- **Tests in `composeApp` commonTest**, not `shared`.
- **ALWAYS `rm -rf composeApp/build/xcode-frameworks` before an iOS build after any Kotlin edit.**
- **Swift edits are a Swift-only native module** called through the existing `ArNativeSurface` cinterop bridge.
- **`Coordinates` is `(lng, lat)`** — use named args; GeoJSON order `[lng, lat]`.
- **RealityKit iOS 18+ features guarded for 17.2** (`if #available(iOS 18.0, *)`); this plan adds none.
- **Commit trailer:** `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
- **Never gate capture or scan submission on quality** — the quality gate changes SHADE TIER ONLY; `fanHitQuality`/`wireQualityForSegment` submission stays ungated.

**Test command (pure logic):** `./gradlew :composeApp:jvmTest --tests "<fqcn>"` (faster than the iOS sim target; both run commonTest).

---

## Task 1 — Quality-gated deep tier in `CoverageTracker`

**Files:** `CoverageTracker.kt`, `ScanQualityScorer.kt` (add `meetsDeepScanQuality`), `CoverageTrackerQualityTest.kt` (+cases), `ScanQualityScorerTest.kt` (+cases).

**Interfaces:**
- `fun coverageQualityForDistanceGated(distanceMeters: Double, deepAllowed: Boolean): CoverageQuality?` — caps a CLOSE result to GRAZED when `!deepAllowed`.
- `fun CoverageTracker.updateWithRaycastHits(hits, deepAllowed: Boolean = true, nowMs = nowEpochMs())` — new optional param (default true preserves callers).
- `fun ScanQualityScorer.meetsDeepScanQuality(signal): Boolean = tierOf(score(signal)) == GOOD`.

- [ ] Step 1: Add tests: `closeHitBelowGoodQualityCreditsGrazedNotClose` (deepAllowed=false, 2m → GRAZED, closePercent 0), `closeHitAtGoodQualityCreditsClose` (deepAllowed=true, 2m → CLOSE), `qualityGateNeverAffectsSeenOrGrazedBands` (7m→GRAZED, 40m→SEEN even when false), `goodCloseCreditIsNotDowngradedByLaterUngatedCloseHit` (monotonic), `coverageQualityForDistanceGatedCapsCloseWhenNotAllowed`; and `meetsDeepScanQualityRequiresGoodTier` (NORMAL/5m/0.8 → true; LIMITED → false).
- [ ] Step 2: Run — expect FAIL: `./gradlew :composeApp:jvmTest --tests "com.gcc.app.platform.CoverageTrackerQualityTest" --tests "com.gcc.app.platform.ScanQualityScorerTest"`
- [ ] Step 3: Implement. In `ScanQualityScorer`:
```kotlin
    /** Deep-scan quality bar (WS3): CLOSE shade only locks in on a GOOD scan.
     *  Gates SHADE TIER ONLY — capture/submission stay ungated. */
    fun meetsDeepScanQuality(signal: ScanQualitySignal): Boolean =
        tierOf(score(signal)) == ScanQualityTier.GOOD
```
In `CoverageTracker.kt` after `fanHitQuality`:
```kotlin
/** [coverageQualityForDistance] with the WS3 deep gate: a <=CLOSE_RANGE hit
 *  only reaches CLOSE when [deepAllowed]; else caps at GRAZED. SEEN/GRAZED
 *  bands unaffected. Null beyond the fan ceiling. */
fun coverageQualityForDistanceGated(distanceMeters: Double, deepAllowed: Boolean): CoverageQuality? {
    val raw = coverageQualityForDistance(distanceMeters) ?: return null
    return if (raw == CoverageQuality.CLOSE && !deepAllowed) CoverageQuality.GRAZED else raw
}
```
Change `updateWithRaycastHits` to add `deepAllowed: Boolean = true` and use `coverageQualityForDistanceGated(hit.distanceMeters, deepAllowed)`. Monotonicity preserved by `creditCell` (upgrade-only); `purgeSince` untouched.
- [ ] Step 4: Run — expect PASS (incl. pre-existing tests via default true).
- [ ] Step 5: Commit `feat(coverage): quality-gate the deep CLOSE tier in CoverageTracker`

---

## Task 2 — Wire the live `ScanQualitySignal` into the per-tick credit

**Files:** `SpotScreen.kt`, `RouteScanViewModel.kt`, `ArRouteScreen.kt`. (UI/VM wiring; no new test — `meetsDeepScanQuality` tested in T1; verified by compile + device walk.)

**Interfaces:** `RouteScanViewModel.onCoverageRayHits(hits, deepAllowed: Boolean = true)`.

- [ ] Step 1: SpotScreen — add off-composition holder `val deepScanAllowed = remember { object { var value = true } }` near `bestFanQualitySinceLastEvent`; import `ScanQualityScorer`. Where the badge signal is assembled, add `LaunchedEffect(liveScanSignal) { deepScanAllowed.value = ScanQualityScorer.meetsDeepScanQuality(liveScanSignal) }` using `ScanQualitySignal(trackingState=arTracking, gpsAccuracyMeters=gpsAccuracy, detectionConfidence=liveDetections.maxOfOrNull { it.confidence ?: 0f })`. In `setCoverageHitListener`, pass `tracker.updateWithRaycastHits(hits, deepAllowed = deepScanAllowed.value)`. Leave `fanHitQuality` ungated.
- [ ] Step 2: RouteScanViewModel — `onCoverageRayHits(hits, deepAllowed: Boolean = true)` → `coverageTracker.updateWithRaycastHits(hits, deepAllowed = deepAllowed)`. ArRouteScreen — compute `deepAllowed` from route-overlay trackingState + accuracy + detections if in scope; else pass `deepAllowed = true` with `// TODO(WS3): thread route-overlay quality signal` (env-scan is the iOS-first surface).
- [ ] Step 3: `rm -rf composeApp/build/xcode-frameworks && ./gradlew :composeApp:compileKotlinIosSimulatorArm64` → PASS. Commit `feat(scan): feed live scan-quality signal into deep-tier credit gate`

---

## Task 3 — Scene-aware beam envelope (pure function)

**Files:** Create `composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/VisibilityBeam.kt`; test `VisibilityBeamTest.kt`.

**Interfaces:** `fun visibilityBeamPolygon(selfLat, selfLng, hits: List<RaycastCoverageHit>, binCount: Int = 16, clampMeters: Double = CoverageTracker.SEEN_RANGE_METERS): List<Pair<Double,Double>>` — closed `(lat,lng)` ring `[self, …arc…, self]`; empty when no hits.

- [ ] Step 1: Failing test (planar-meters helper; hit-at-bearing helper): no hits → empty; ring starts/ends at self; near-only → short reach (<5m), far → long (>40m); reach clamps to SEEN_RANGE; per-bin max keeps the farther hit.
- [ ] Step 2: Run — expect FAIL (unresolved `visibilityBeamPolygon`).
- [ ] Step 3: Implement (see below): bin hits by bearing `atan2(east, north)` into `binCount` bins, keep max reach per bin (clamped), sort by bin, build closed ring from self out through each bin's `(reach, bearing)`. Uses `METERS_PER_DEG_LAT`, `lngScaleFor(selfLat)`.
```kotlin
package com.gcc.app.ui.map

import com.gcc.app.platform.CoverageTracker
import com.gcc.app.platform.METERS_PER_DEG_LAT
import com.gcc.app.platform.RaycastCoverageHit
import com.gcc.app.platform.lngScaleFor
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Scene-aware flashlight beam (WS2): from the user dot, a polygon whose reach
 *  per heading bin is the FARTHEST ray landing in that bin this tick, clamped
 *  to [clampMeters]. Long across open street, short on near ground, truncated
 *  at walls. Closed (lat,lng) ring; empty when no hits. Pure + tested. */
fun visibilityBeamPolygon(
    selfLat: Double, selfLng: Double, hits: List<RaycastCoverageHit>,
    binCount: Int = 16, clampMeters: Double = CoverageTracker.SEEN_RANGE_METERS,
): List<Pair<Double, Double>> {
    if (hits.isEmpty()) return emptyList()
    val lngScale = lngScaleFor(selfLat)
    val reachByBin = HashMap<Int, Pair<Double, Double>>()
    for (h in hits) {
        val north = (h.lat - selfLat) * METERS_PER_DEG_LAT
        val east = (h.lng - selfLng) * METERS_PER_DEG_LAT * lngScale
        val dist = sqrt(north * north + east * east)
        if (dist <= 0.0) continue
        val reach = if (dist > clampMeters) clampMeters else dist
        var bearing = atan2(east, north); if (bearing < 0.0) bearing += 2.0 * PI
        val bin = ((bearing / (2.0 * PI)) * binCount).toInt().coerceIn(0, binCount - 1)
        val prev = reachByBin[bin]
        if (prev == null || reach > prev.first) reachByBin[bin] = reach to bearing
    }
    if (reachByBin.isEmpty()) return emptyList()
    val latPerMeter = 1.0 / METERS_PER_DEG_LAT
    val lngPerMeter = 1.0 / (METERS_PER_DEG_LAT * lngScale)
    val arc = reachByBin.entries.sortedBy { it.key }.map {
        val (reach, bearing) = it.value
        (selfLat + reach * cos(bearing) * latPerMeter) to (selfLng + reach * sin(bearing) * lngPerMeter)
    }
    return ArrayList<Pair<Double, Double>>(arc.size + 2).apply {
        add(selfLat to selfLng); addAll(arc); add(selfLat to selfLng)
    }
}
```
Note: verify `lngScaleFor` + `METERS_PER_DEG_LAT` exist in `com.gcc.app.platform`; if named differently, adapt import (they back `CoverageTracker`'s own geo math).
- [ ] Step 4: Run — expect PASS. Commit `feat(scan): scene-aware visibility-beam envelope from ray-fan hits`

---

## Task 4 — Render the beam on the minimap; retire the fixed cone there

**Files:** `ScanMinimapOverlay.kt`, `CameraCapture.kt`, `SpotScreen.kt`, `RouteScanViewModel.kt`, `ArRouteScreen.kt`. (Rendering/wiring; geometry tested in T3, bias in `MapOverlayZoomBiasTest`.)

**Interfaces:** `ScanMinimapOverlay(..., visibilityBeam: List<Pair<Double,Double>> = emptyList())`; `CameraCapture(..., visibilityBeam = emptyList())`; `RouteScanViewModel.visibilityBeam: StateFlow<List<Pair<Double,Double>>>`.

- [ ] Step 1: ScanMinimapOverlay — add `visibilityBeam` param; in the Canvas draw it AFTER the veil, BEFORE the session-tier outlines: build a Path through `toOffset(lat,lng)`, `close()`, `drawPath(beamPath, GccGreen.copy(alpha=0.12f))` (guard `size >= 3`).
- [ ] Step 2: CameraCapture — add `visibilityBeam` param, pass to `ScanMinimapOverlay`; set `gpsHeading = null` on the minimap `GccMap` (beam supersedes the fixed cone). (The "You are here" bar removal happens in Task 9.)
- [ ] Step 3: SpotScreen — `var visibilityBeam by remember { mutableStateOf<List<Pair<Double,Double>>>(emptyList()) }`; in `setCoverageHitListener`, after crediting, `locationProvider.currentLocation.value?.let { visibilityBeam = visibilityBeamPolygon(it.lat, it.lng, hits) }`; pass `visibilityBeam = visibilityBeam` to the env-scan `CameraCapture`.
- [ ] Step 4: RouteScanViewModel — add `_visibilityBeam` MutableStateFlow + public `visibilityBeam`; in `onCoverageRayHits`, `_visibilityBeam.value = visibilityBeamPolygon(s.currentLat, s.currentLng, hits)`. ArRouteScreen — collect + pass to `ScanMinimapOverlay`; set route minimap `gpsHeading = null`.
- [ ] Step 5: `rm -rf composeApp/build/xcode-frameworks && ./gradlew :composeApp:compileKotlinIosSimulatorArm64` → PASS. Commit `feat(scan): render scene-aware beam on minimap, retire fixed cone there`

---

## Task 5 — Merged coverage on the minimap (server-fresh/stale/corridor)

**Files:** `ScanMinimapOverlay.kt`. No signature change.

- [ ] Step 1: Guard test in `CoverageOutlineTest.kt`: two edge-adjacent 10m cells → exactly ONE merged ring (no interior seam). (Should PASS immediately — the engine already merges; it's a regression guard for the refactor.)
- [ ] Step 2: Run: `./gradlew :composeApp:jvmTest --tests "com.gcc.app.ui.map.CoverageOutlineTest"` → PASS. (If it fails, the refactor premise is wrong — stop.)
- [ ] Step 3: Implement — hoist `corridorRings/freshRings/freshCloseRings/staleRings` via `quantizeAndTraceOutlines(cells, cellSize, latticeAnchorLat, latticeAnchorLng)` (freshness sets use `freshnessCellSizeMeters`). Build their paths (`ringsToPath`). Rework the veil hole-punch to add merged outlines (corridor+fresh+freshClose+scan) instead of per-cell `punch`. Replace corridor/fresh/freshClose/stale per-cell fill loops with merged-outline `drawPath` at the SAME colors/alphas (corridor VeilSlate α0.28, fresh GccGreen α0.07, freshClose α0.16, stale StaleAmber α0.45). Delete now-unused `drawCell`/`cellRect`/`punch` (grep first) + unused imports. Session-tier fills, boundary rings, glyphs, guidance ring, self dot unchanged.
- [ ] Step 4: `./gradlew :composeApp:jvmTest --tests "com.gcc.app.ui.map.CoverageOutlineTest"` + `rm -rf composeApp/build/xcode-frameworks && ./gradlew :composeApp:compileKotlinIosSimulatorArm64` → PASS. Commit `refactor(minimap): merge ledger coverage tiers into organic outlines`

---

## Task 6 — Merged coverage on the main map + merged fog holes

**Files:** `GeoJsonLayers.kt`, `GccMap.ios.kt`, test `CoverageStateGeoJsonsTest.kt`.

**Interfaces:** `buildCoverageStateGeoJsons(...)` signature unchanged (callers always pass `useOutlines=true`). `CoverageStateJsons` unchanged.

> NOTE: `MapCoverageCell` requires ALL of: `locationCellId, lat, lng, state, lastScannedAt, totalDistanceMeters, quality, priority`. Test fixtures MUST pass `lastScannedAt` and `totalDistanceMeters` (agent draft omitted them).

- [ ] Step 1: Failing test — two adjacent "close" cells with `useOutlines=true`: `clean` collection must NOT contain `"cellId"` (rings span cells); `fog` contains `Polygon`.
- [ ] Step 2: Run — expect FAIL (dirty/stale emit per-cell quads with cellId; fog uses squareRing).
- [ ] Step 3: Implement — route `dirty` and `stale` through `qualityCollection` (which already switches to `outlineFeatures` under `useOutlines`). Replace the fog hole builder: when `useOutlines`, punch merged rings via `quantizeAndTraceOutlines(inBounds.map{it.lat to it.lng}, cellSizeMeters, anchorLat, anchorLng)` (each ring → `[[lng,lat],...]`), else keep the per-cell square branch. Keep `priority` lens per-cell (needs numeric `priority` property — documented exception); `squareRing` stays (used by priority + `buildChoroplethGeoJson`). In `GccMap.ios.kt` set `val useOutlineCoverage = true` (drop the zoom gate).
- [ ] Step 4: Run — expect PASS. Commit `refactor(map): all coverage buckets + fog holes use merged outlines`

---

## Task 7 — Preserve tap-to-info via lat/lng→cell hit-test

**Files:** Create `composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/CoverageCellHitTest.kt`; test `CoverageCellHitTestTest.kt`; modify `GccMap.ios.kt` (add `onMapClick`).

**Interfaces:** `fun coverageCellIdAt(lat, lng): String = gridCell10m(lat, lng)`. `MaplibreMap(onMapClick = { pos, _ -> ... })` where `pos: org.maplibre.spatialk.geojson.Position` (`pos.latitude/longitude`).

- [ ] Step 1: Failing test — `coverageCellIdAt(lat,lng) == gridCell10m(lat,lng)`; two points ~a couple meters apart inside one ~10m cell → same id.
- [ ] Step 2: Run — expect FAIL (unresolved `coverageCellIdAt`).
- [ ] Step 3: Implement `coverageCellIdAt = gridCell10m`. In `GccMap.ios.kt` `MaplibreMap(...)` add `onMapClick`: resolve `coverageCellIdAt(pos.latitude, pos.longitude)`; if `coverageCells.any { it.locationCellId == cellId }` and `onCoverageCellTap != null` → `tap(cellId); ClickResult.Consume` else `ClickResult.Pass`. Layer-level `cellIdClickHandler` unchanged (Passes for cellId-less outline features → falls through to `onMapClick`).
- [ ] Step 4: Run — expect PASS; `rm -rf composeApp/build/xcode-frameworks && ./gradlew :composeApp:compileKotlinIosSimulatorArm64` → PASS. Commit `feat(map): tap-to-info via lat/lng→cell hit-test on merged coverage`

---

## Task 8 — Remove the neon AR coverage quads (env-scan surface)

**Files:** `composeApp/src/iosMain/kotlin/com/gcc/app/platform/ArEnvScan.kt`, `iosApp/iosApp/GccArNative.swift`. No signature change; no unit test (device-only). Verified by compile + device walk (no neon rectangle).

- [ ] Step 1: ArEnvScan.kt — replace `renderVectorCells()` body with a no-op teardown: `nativeSurface?.updateVectorCells(emptyList(), CoverageTracker.cellSizeMeters)`; remove all `vectorNodes` from parent + clear. Keep `updateScannedCells` caller. Grep + delete now-unused `makeVectorCellNode`/`vectorTexture`/`MAX_VECTOR_NODES`/`VECTOR_RENDER_RADIUS_METERS` only if the compiler warns-as-error.
- [ ] Step 2: GccArNative.swift — gut `updateVectorCells(flatXZ:cellSizeMeters:)` to tear down existing `vectorCellEntities` (animate out + remove) and return, ignoring incoming centers. Keep `gridMaterial` (ground grid), `addPulse`, `animateIn/Out`. Confirm the ground grid uses `setGridPhaseOrigin`, not vector cells (stays crisp).
- [ ] Step 3: `rm -rf composeApp/build/xcode-frameworks && ./gradlew :composeApp:compileKotlinIosSimulatorArm64` → PASS. Commit `fix(ar): remove neon coverage quads from env-scan AR surface`

---

## Task 9 — Declutter the env-scan screen

**Files:** `CameraCapture.kt` (remove "📍 You are here" bar), `EnvironmentScanEffects.kt` (drop 3.2s sweep, keep credit-flash), `SpotScreen.kt` (light restyle Complete pill — keep as sole exit). UI-only; verified by build + device walk.

- [ ] Step 1: CameraCapture — delete the "You are here" `Box`/`Row` block entirely; drop now-unused imports (grep). EnvironmentScanEffects — replace body: `if (!active) return`; keep only a per-`creditTick` decaying pulse (peak α0.18, `*=0.8` every 30ms) drawn as a full-screen `drawRect(GccGreen.copy(alpha=pulseAlpha))`; delete the infinite-sweep code + unused animation imports (grep). SpotScreen — Complete pill: ensure label reads `"Complete Scan ✓"`, `fontWeight = SemiBold`, white text in both enabled/dim states; do NOT touch `canComplete`/`clickable`.
- [ ] Step 2: `rm -rf composeApp/build/xcode-frameworks && ./gradlew :composeApp:compileKotlinIosSimulatorArm64` → PASS. Commit `chore(scan): declutter env-scan screen (drop You-are-here + sweep)`

---

## Release gate (manual device walk test)

iOS device: beam lengthens across open street, shortens/truncates on near ground + walls; deep (dark) shade only on GOOD close scans (LIMITED → grazed only); coverage merges like paint on minimap + main map, no interior grid lines; no neon rectangle; "You are here" bar + sweep gone; tap-to-info still opens the last-scan card. (Indoor-building suppression is WS1 — separate plan.)

## Self-review

WS2 beam envelope (T3) + minimap render/retire cone (T4); WS3 quality-gated deep tier (T1) + live signal wiring (T2); WS4 minimap merge (T5) + main-map merge & fog holes (T6) + tap hit-test (T7); WS5 neon removal both paths (T8); WS6 declutter (T9). No placeholders (one conditional route-path TODO in T2, env-scan fully wired). Names consistent: `visibilityBeamPolygon`, `visibilityBeam` param/flow, `deepAllowed`, `meetsDeepScanQuality` (one def), `coverageCellIdAt`==`gridCell10m`==`MapCoverageCell.locationCellId`. `priority` stays per-cell by design; `squareRing` retained for priority + choropleth.
