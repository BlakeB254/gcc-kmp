# Scan Visibility Rework + Indoor Footprint Gate — Design

**Date:** 2026-07-08
**Status:** Approved for planning (brainstorm complete)
**Scope:** One TestFlight build. iOS-first (environment-scan surface); Android parity where the change is in `commonMain`.

## Problem

Field feedback on the environment-scan experience, four related issues:

1. **Inside a building still registers coverage.** Standing inside a house that
   is clearly a building on the basemap, the app keeps crediting scan coverage.
   The indoor gate's building-containment signal only knows ~28 hardcoded
   rectangles for North Lawndale (`BuildingData.kt:northLawndaleBuildings` /
   `isInsideMappedBuilding` L205), so anywhere else the map-evidence signal
   never fires and the gate leans on camera/ceiling/GPS voting, which is
   unreliable indoors.

2. **Coverage reads as squares, not "painting the town."** On both the minimap
   and the main map, scanned areas show hard cell edges / grid lines between
   adjacent scanned cells. The desired feel: adjacent scanned areas merge and
   touch like paint, ragged where scanning stopped, never perfectly tiled.

3. **Neon green rectangle bleeding into the camera view.** A glowing green
   rectangle low in the AR view, same hue as the ground grid but blurry/pulsing
   while the grid itself stays crisp.

4. **Cluttered env-scan screen.** Green elements whose purpose isn't clear.

The rework also introduces the intended *visibility model*: a scene-aware
"flashlight" cone from the user dot, with light shading for glancing (SEEN)
coverage and dark shading only for genuinely good close scans.

## Grounding: what already exists

Much of the target behavior is partially built (V2 spec Pillar 7). Confirmed in
code:

- **SEEN/GRAZED/CLOSE tiers** — `CoverageTracker.kt` (`CoverageQuality` L22,
  distance bands `CLOSE_RANGE_METERS=4`, `GRAZE_RANGE_METERS=10`,
  `SEEN_RANGE_METERS=55`). The raycast fan (`CoverageRayFan.kt`) records where
  each of 15 image-space rays *lands* (distance + geo point) and credits the
  cell it hit, tiered by distance.
- **Organic marching-squares rendering** — `CoverageOutline.kt`
  (`traceCoverageOutlines`, `chaikinSmooth`, `quantizeAndTraceOutlines`). The
  minimap (`ScanMinimapOverlay.kt`) already draws *session* tiers as smoothed
  merged blobs (SEEN α0.11 / GRAZED α0.30 / CLOSE α0.65). The main map has an
  outline mode (`GeoJsonLayers.kt:buildCoverageStateGeoJsons(useOutlines=…)`,
  `outlineFeatures()` L940).
- **2D heading cone** — `HeadingCone.kt:headingConeGeoJson()` renders a fixed
  22m/55° sector as a cosmetic flat-map indicator (`user-location-heading-cone`
  layer). Not scene-aware, not tied to coverage.
- **Indoor gate v4** — `OutdoorGate.kt` fuses ceiling planes
  (`GccArNative.ceilingDetected()`), scene votes
  (`GccArNative.classifySceneLabels()`), GPS accuracy, and map evidence
  (`onMapEvidence`, `MAP_STREAK_TO_FLIP=1` → instant INDOOR). INDOOR flip
  purges in-window credit (`CoverageTracker.purgeSince`) and retracts on the
  server (`POST /api/scans/coverage/retract`).
- **Scan quality scorer** — `ScanQualityScorer.kt` fuses AR `trackingState`
  (weight 0.5), GPS accuracy (0.3), and `detectionConfidence` (0.2) into a 0–1
  score with tiers GOOD ≥0.7 / FAIR ≥0.4 / POOR. **Currently display-only**
  (`ScanQualityBadge.kt`) — nothing gates on it.
- **Session → world model** — in-memory `CoverageTracker` StateFlows feed the
  minimap live; `CoverageEventQueue` flushes events every 30s + on complete to
  `POST /api/scans/coverage`; `site/src/lib/scan-coverage.ts:recordCoverageEvent`
  rolls them into `cellCoverageSummary`; `GET /api/map/coverage` serves them
  back as `MapCoverageCell`s (world model). Retract recomputes each cell's
  summary from the surviving event log.

So this is **finish + fix + declutter**, not build-from-scratch.

## Design

Six workstreams, one build.

### 1. Indoor gate — real building footprints

Replace hardcoded-rectangle containment with real OSM building polygons,
cached server-side, checked client-side by point-in-polygon.

- **Server:** new Neon table `building_footprints` (id, `polygon` GeoJSON,
  bbox columns for indexed lookup, `building_type`/tags, `source`,
  `fetched_at`). New route `GET /api/map/buildings?bbox=` under
  `site/src/app/api/map/buildings/`: returns footprints intersecting the
  bbox; on a tile cache-miss, lazily fetches from Overpass, upserts, returns.
  bbox is tile-quantized (like `scanAreaCache` ~1.1km tiles) and cached with a
  TTL (30d) so a tile is fetched once. Follows the schema-ownership policy
  (schema.ts authoritative; `Tables.kt` mirror if the Ktor server ever reads
  it — it does not today, so mirror is optional but note in PR).
- **Client:** `BuildingFootprintStore` (SQLDelight, mirroring
  `ScanAreaCacheStore`) caches fetched footprints per tile. `BuildingData.kt`
  gains a real `isInsideMappedBuilding(lat,lng)` doing **point-in-polygon**
  over cached footprints (new shared ray-casting util in `com.gcc.shared.geo`),
  with the existing North Lawndale rectangles retained as an **offline
  fallback** when no footprints are cached for the area.
- **Gate wiring:** unchanged shape — `onMapEvidence(insideBuilding = …)` in
  `RouteScanViewModel.kt` (L1337) and `SpotScreen.kt` (L485) now receives real
  containment. Inside a building → instant INDOOR (existing
  `MAP_STREAK_TO_FLIP=1`), `captureAllowed=false`, credit suppressed, cone
  hidden, in-window purge + retract (existing).

**Failure handling:** Overpass unreachable/timeout (known 504 history) → serve
whatever is cached, return empty for uncached tiles, log; client falls back to
the hardcoded set and camera/ceiling/GPS voting — i.e. no regression vs today.
Never block a scan on the footprint fetch.

### 2. Scene-aware flashlight cone (minimap)

Draw a live beam from the user dot whose extent follows what the phone can
actually see this tick — the envelope of the current fan's ray landings, fanned
around AR heading, clamped to `SEEN_RANGE_METERS`.

- **Data:** `CoverageRayFan.cast()` already yields per-ray
  `RaycastCoverageHit(lat,lng,distanceMeters)`. Expose the current tick's hits
  (or a derived per-heading-bin max-reach envelope) from the tracker/overlay to
  the minimap.
- **Render:** new `visibilityBeam` polygon in `ScanMinimapOverlay.kt`, drawn
  from `selfCenter` outward through the hit envelope. Long across open street
  (far hits), short when aimed at near ground (near hits), truncated at walls
  (rays stop on surfaces). Subtle fill (flashlight feel), under the coverage
  outlines. Retire / replace the fixed-radius `headingConeGeoJson` indicator
  where the scene-aware beam supersedes it (keep the flat-map heading cone on
  the main map if still useful outside scan sessions — decide in plan).

### 3. Tiers: physical + quality-gated deep shade

Keep tiers physical (driven by fan geometry), add a quality gate to the deep
tier. No dwell timer.

- **SEEN (light):** far ray hits (10–55m). Glancing across the street.
- **GRAZED (mid):** mid-range hits (≤10m).
- **CLOSE / deep (dark):** a close-range hit (≤4m) **AND**
  `ScanQualityScorer.score(signal) >= GOOD_THRESHOLD` (0.7) at credit time.
  If close but quality is below GOOD, the cell credits GRAZED, not CLOSE — the
  dark paint only locks in on a genuinely good scan.
- **Tap:** tapping an item still credits deep at that spot (instant).

**Wiring:** `ScanQualitySignal` (trackingState + gpsAccuracy +
detectionConfidence) must be available at the credit path
(`CoverageTracker.updateWithRaycastHits` / the per-tick credit in
`RouteScanViewModel`). Introduce a `fanHitQuality`-equivalent that takes the
quality tier into account so a ≤4m hit only reaches `CLOSE` when quality is
GOOD. This gates *shade tier only* — capture and submission stay voluntary and
ungated (preserving the scorer's documented contract). The monotonic
upgrade + prior-chain (`creditCell`, `purgeSince`) is unchanged, so an
INDOOR/quality revert still works.

### 4. Paint the town — merged coverage everywhere

Route **all** coverage through the merged organic-outline engine; delete the
square renders.

- **Minimap (`ScanMinimapOverlay.kt`):** session tiers already use outlines.
  Move the coarser **server-fresh / stale / corridor** cells off `drawCell` /
  `cellRect` (`drawRect` quads, L135-147) onto `quantizeAndTraceOutlines` so
  they merge with each other and with session coverage — no interior grid
  lines. Ragged boundary is expected and fine.
- **Main map (`GeoJsonLayers.kt` / `GccMap.ios.kt`):** make `useOutlines=true`
  the only coverage path; drop `cellQuad` / `squareRing` square-polygon
  features. Fog holes (`fog` FeatureCollection) punch the **merged outline
  shape**, not per-cell squares.
- **Tap-to-info preserved:** outline polygons currently carry no `cellId`
  (unlike square features). Preserve tap-to-info by hit-testing the tapped
  lat/lng back to a cell (`CoverageTracker.cellForLatLng` / `gridCell10m`) in
  the tap handler (`cellIdClickHandler`, `GccMap.ios.kt` L1490) instead of
  reading a feature property. **This is real work, flagged as a sub-task.**

### 5. Remove the neon rectangle / camera bleed

The emissive pulsing coverage quads are the culprit (and the "green neon
rectangle" the user sees low in the view):

- SceneKit path — `ArEnvScan.kt` vector-cell nodes (L1348-1374): neon
  `(0.15,0.95,0.55)`, `emission` set, `writesToDepthBuffer=false`, shimmer.
- RealityKit path — `GccArNative.swift:updateVectorCells` (L267-307): same
  neon family, `emissiveIntensity=2.0`, scale pulse, floats at groundY+0.02.

**Remove the coverage vector-cell overlay from the env-scan AR surface** on
both paths. The matte line-only ground grid stays (reads crisp). Coverage
feedback now lives on the minimap (cone + merged paint). Leave route/spot AR
overlays untouched unless the same neon reads badly there (out of scope for
this build unless trivial).

### 6. Declutter the env-scan screen

- **Remove** the decorative "📍 You are here" bar (`CameraCapture.kt`
  L262-286) — no action, pure clutter.
- **Soften or remove** the `EnvironmentScanEffects` green sweep band
  (`EnvironmentScanEffects.kt`; invoked `SpotScreen.kt` L1040). Keep at most a
  subtle credit-flash; drop the 3.2s sweep wash.
- **Keep** the "Complete Scan ✓" pill (`SpotScreen.kt` L1135) — it is the sole
  exit. Light restyle only (clarity), no removal.

## Data flow (unchanged spine)

Fan hits (per tick) → tier classification (now quality-aware) →
`CoverageTracker` StateFlows → minimap (live cone + merged outlines) →
`CoverageEventQueue` (30s + on-complete flush) → `POST /api/scans/coverage` →
`cellCoverageSummary` rollup → `GET /api/map/coverage` (world model) → main map
merged outlines. INDOOR flip (now footprint-driven) or sub-GOOD quality →
purge/downgrade in-window + server retract.

## Testing

- **CoverageTracker:** deep tier requires close hit AND GOOD quality
  (unit test with quality signal permutations); SEEN/GRAZED unaffected by
  quality; monotonic upgrade + purge revert still hold. (`composeApp`
  commonTest — `shared` commonTest is broken per repo convention.)
- **Point-in-polygon:** shared geo util test (inside/on-edge/outside, holes not
  required for buildings).
- **Footprint store/route:** bbox-quantization + cache-hit returns without
  Overpass; cache-miss path mockable.
- **Outline merge:** adjacent cells produce one merged ring, no interior edges
  (`CoverageOutlineTest` extension); fog hole matches merged shape.
- **Beam envelope:** near-only hits → short beam; far hits → long beam;
  mixed → correct per-heading reach.
- **Manual device walk test (release gate):** inside a mapped building → no
  credit, cone hidden; flashlight lengthens across street / shortens on
  ground; deep shade only on good close scans; no neon rectangle; decluttered
  screen; tap-to-info still works on merged coverage.

## Risks / open nuances

- **Overpass reliability** — mitigated by lazy server cache + client fallback;
  never gates a scan.
- **Tap targets on merged outlines** — addressed by lat/lng→cell hit-testing;
  called out as its own sub-task.
- **Quality signal plumbing** — `detectionConfidence` and `trackingState` must
  actually reach the per-tick credit path; verify they're live during env scan
  (route/spot already feed `ArSessionHealth`).
- **Beam data volume** — beam derives from the same fan already computed; no
  new sensing, minimal overhead.
- **`mapOverlayZoomBias`** — any new minimap geometry (beam) must project with
  `cameraZoom + mapOverlayZoomBias(density)` (see `gcc-maplibre-overlay-scale`).

## Out of scope

- Route/spot AR coverage-quad restyle (env-scan only this build, unless
  trivially shared).
- Server-side building-type policy (e.g. "residential refuses manual
  override") — footprint containment only for now.
- Android device verification (parity compiles; walk test is iOS).
