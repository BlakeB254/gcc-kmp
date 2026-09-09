# Scan & Coverage Model — how the app actually works

**Status:** living reference. Last updated 2026-07-09 (post scan-visibility rework + indoor-footprint gate).

This is the one place that explains the scan/coverage subsystem end-to-end. It is a **map that points into the code**, not a re-derivation — the load-bearing details live in KDocs on the files below and are kept there. Read this to understand *how the pieces fit*; open the cited files for the *why* and the exact thresholds.

> Scope: the environment scan (spot) and block-cleaning route scan, the coverage tiers, the on-map rendering, the indoor gate, and how a session becomes persistent "world model" coverage + points. For the block-route completion state machine specifically, see also `app-map-mobile.md`.

---

## 1. The mental model

A worker points their phone at the world; the app credits the **ground cells** the camera/GPS can attest to, in three quality tiers, and paints them onto a minimap (live) and the world map (after upload). Unscanned ground is under a fog **veil**; scanning punches it out. Inside a building, nothing credits.

Two capture surfaces exist and share the same coverage core:
- **Environment scan** (`SpotScreen` with `environmentScanMode`) — free-form "scan around you."
- **Block-cleaning route** (`RouteScanViewModel` + `ArRouteScreen`) — a lap over an assigned block boundary.

---

## 2. Coverage tiers (the core contract)

`composeApp/.../platform/CoverageTracker.kt` — `enum CoverageQuality { SEEN, GRAZED, CLOSE }`. **Ordinal order is the contract** (`SEEN < GRAZED < CLOSE`); credits only ever upgrade (monotonic), with a prior-chain kept so an INDOOR flip can revert in-window credit (`creditCell`, `purgeSince`).

Distance bands (`coverageQualityForDistance`, companion constants):
- **SEEN** — a ray landed 10–55m out ("the camera saw it," e.g. across the street). Faintly shaded; **excluded from coverage% and never punched out of the fog** (sight ≠ worked ground).
- **GRAZED** — ≤10m. Light shade; counts as "scanned."
- **CLOSE** — ≤4m **and** the scan was GOOD quality (`coverageQualityForDistanceGated` + `ScanQualityScorer.meetsDeepScanQuality`, ≥0.7). Dark shade = "we genuinely got this." Without GOOD quality, a close hit caps at GRAZED. `ScanQualityScorer` fuses AR trackingState (0.5) + GPS accuracy (0.3) + detectionConfidence (0.2).

The cell grid is a fixed ~3m lattice; `cellSizeMeters`, `cellForLatLng`, `cellCenterLatLng`.

## 3. What credits a cell

- **Raycast fan** (`composeApp/src/iosMain/.../CoverageRayFan.kt`) — per tick casts a 3×5 image-space grid of rays; each ray that lands on a real surface within 55m becomes a `RaycastCoverageHit(lat,lng,distanceMeters)`. Rays stopping on surfaces is the *structural* reason indoor over-credit is bounded. `CoverageTracker.updateWithRaycastHits(hits, deepAllowed)` credits each hit's cell by its tiered distance.
- **GPS floor** (`updatePosition`) — cells within `scanRadiusMeters` (5m) of the fix credit GRAZED, so GPS-only devices still make progress.

## 4. Live rendering — the minimap

`composeApp/.../ui/components/ScanMinimapOverlay.kt` (a Compose `Canvas` over a fixed-zoom `GccMap`). Bottom→top paint order: **veil → flashlight beam → corridor/ledger tiers → session tiers → boundaries → trash → guidance → user dot.**

- **Fog veil** — one dark full-canvas rect with holes punched geometrically (`Path.combine(Difference)`; never `BlendMode.Clear` — fragile on iOS Skia). SEEN cells are NOT punched.
- **Organic outlines ("paint the town")** — every coverage tier (session + server ledger + corridor) is traced through marching-squares + Chaikin smoothing (`CoverageOutline.kt::quantizeAndTraceOutlines`, hoisted via `rememberRings`) so adjacent cells merge with no interior grid lines.
- **Scene-aware flashlight beam** (`ui/map/VisibilityBeam.kt::visibilityBeamPolygon`) — a radial-gradient wedge from the user dot built from the *current tick's ray-hit envelope*: long across open street, short when aimed at near ground, truncated at walls. Hidden when capture isn't allowed (indoors).
- **Projection invariant:** any Canvas geometry over a MapLibre map MUST bias zoom by `MapStyles.mapOverlayZoomBias(density) = log2(density×2)` (see that KDoc). All overlay layers project through the same `toOffset`.

## 5. On-map rendering — the world map

`composeApp/.../ui/map/GeoJsonLayers.kt::buildCoverageStateGeoJsons` → MapLibre fill/line layers in `GccMap.ios.kt`. Coverage always renders as **merged organic outlines** (`useOutlineCoverage = true`); fog holes take the merged shape. Outline features carry no `cellId`, so tap-to-info resolves the tapped lat/lng back to a cell via `CoverageCellHitTest.kt::coverageCellIdAt` (= `gridCell10m`) in the map-level `onMapClick`. The rescan-priority "needs scanning" lens stays per-cell (it needs a numeric property outlines can't carry).

## 6. The indoor gate

`composeApp/.../platform/OutdoorGate.kt` — deny-by-default state machine (`OUTDOOR / UNCERTAIN / INDOOR`); `captureAllowed` is true only when OUTDOOR. It fuses:
- **Building-footprint containment** — `BuildingData.isInsideMappedBuilding` runs point-in-polygon (`shared/.../geo/PolygonContainment.kt::pointInGeoJsonRing`, `[lng,lat]`) over real OSM footprints held by `MappedBuildingRegistry`, fed by `BuildingFootprintLoader` (cache-first `BuildingFootprintStore` + `GET /api/map/buildings`). Hardcoded North Lawndale rectangles are the offline fallback. GPS inside a mapped building → instant INDOOR (`MAP_STREAK_TO_FLIP=1`).
- **Ceiling planes** (`GccArNative.ceilingDetected`), **camera scene votes** (`GccArNative.classifySceneLabels` Vision), **GPS accuracy**.

On an INDOOR flip, in-window credit is purged locally (`CoverageTracker.purgeSince`) and retracted server-side (`POST /api/scans/coverage/retract`).

> **Known prod gap (2026-07-09):** the Overpass footprint fetch happens synchronously inside the `GET /api/map/buildings` serverless handler and does not complete within the Vercel function limit, so footprints don't populate in prod yet — the gate currently runs on the North Lawndale rectangle fallback. Fix = move Overpass ingestion off the request path (cron/admin pre-populate). Tracked as a follow-up.

## 7. The AR native bridge

`composeApp/src/iosMain/.../ArNativeSurface.kt` (K/N `expect` interface) ↔ `iosApp/iosApp/GccArNative.swift` (RealityKit `actual`), with a SceneKit fallback in `ArEnvScan.kt`. Kotlin keeps ALL policy (fog/coverage/frontier logic) and ships AR-local primitives; Swift renders entities. The camera surface is deliberately minimal — a matte ground grid only; coverage/fog feedback lives on the minimap (the neon coverage quads and AR fog were removed). Interop gotchas (cinterop pointer types dropped from ObjC headers, iOS-18-only RealityKit APIs, boxed KotlinInt/Double) are documented in `GccArNative.swift` + `ArNativeSurface.kt` KDocs.

## 8. Session → world model

In-memory `CoverageTracker` StateFlows drive the minimap live. `CoverageEventQueue` batches credited cells and flushes every 30s + on completion to `POST /api/scans/coverage`; the server (`site/src/lib/scan-coverage.ts::recordCoverageEvent`) appends to an event log, rolls up `cellCoverageSummary` (best tier, `lastScannedAt`), and awards `pointsLedger` points per category (SEEN never pays). `GET /api/map/coverage` serves the rollup back as `MapCoverageCell`s (the world model the main map draws). Retraction recomputes each touched cell's summary from the surviving event log — the log is the source of truth, so retract is safe.

**Cell-key byte-equality is a hard contract:** client `shared/.../geo/GridCell.kt::gridCell10m` must byte-match server `site/src/lib/world-observations.ts::gridCell` (both `111000`/`82300`, NOT `cos(lat)`) — the id names the R2 raw/{cellId}/ path the DGX pipeline groups by. See `docs/CONVENTIONS.md`.

## 9. Scan supersedes spots; world observations

Litter is recorded as `world_observations` (not standalone "spots," which were removed). A scan submission supersedes nearby prior reports; distribution-aware litter viz replaces per-item pins. See `WorldObservation.kt` / `world-observations.ts` KDocs and `feature-spec-2026-05.md` for product intent.

---

## 10. Watch — vision-based localization (not scheduled)

Recorded from the 2026-09-08 X-bookmark round; nothing here is implemented or scheduled.

**The structural observation.** The cell lattice is ~3m (§2), but consumer GPS error on a
street is routinely 5–10m and worse against buildings. So the grid is *finer than the
position estimate that feeds it*. That mismatch is upstream of several defects we have
repeatedly patched downstream: the GPS floor (§3) credits a 5m disc around a fix that may
itself be off by more than a cell, and GPS accuracy is 0.3 of `ScanQualityScorer`, so a bad
fix silently caps otherwise-good close scans at GRAZED (§2). Each fix we have shipped —
gap-closing the silhouette, letting the disc upgrade fan-SEEN cells, widening tiers — has
adjusted how we *spend* an uncertain position rather than improving the position.

**What would change that.** Visual positioning against a prior Gaussian-splat reconstruction
of the route: [RadianceFields 2096520025859399751](https://x.com/RadianceFields/status/2096520025859399751)
localizes from a splat where GPS cannot ("my phone thinks I'm on 42nd Street, but I'm in the
subway two levels down"). Pose would come from what the camera sees, not from the fix — which
is the same signal the raycast fan already depends on. This is the second independent signal
on this idea; the first is logged in
`cdx-platform/docs/research/2026-08-28-x-bookmarks/kb-docs/radiance-vps-gcc-video.md`.

The reconstruction half now looks affordable:
[Scal3R 2096451469029298306](https://x.com/HuggingPapers/status/2096451469029298306) (NVIDIA)
does accurate **online** reconstruction over kilometre-scale sequences with ~1% extra
parameters on a frozen backbone, trained in 8h on a single GPU. Online suits a walked route
rather than an offline batch solve, kilometre scale is a route rather than a lot, and the
training budget fits the Spark. Related: `docs/governance/scan-splat-registration-contract.md`.

**Honest limits.** This is a heavy dependency — a prior splat per route, kept current as the
street changes, plus on-device matching cost and a fallback whenever the match fails. It does
not replace the GPS floor; at best it becomes a fourth, higher-confidence source alongside the
ray fan. And a phone-scale test is not a paper result.

**If it gets picked up**, the bounded first test is a re-scan of an already-captured North
Lawndale strip (sources: `cdx-platform/docs/research/north-lawndale-splat-sources.md`),
comparing VPS-derived pose against the GPS fix on the *same* walk — measuring position delta
and how many cells change tier, before touching `CoverageTracker` at all.

## File index (open these for detail)

| Concern | File(s) |
|---|---|
| Coverage core, tiers, purge | `composeApp/.../platform/CoverageTracker.kt` |
| Raycast fan | `composeApp/src/iosMain/.../platform/CoverageRayFan.kt` |
| Scan quality gate | `composeApp/.../platform/ScanQualityScorer.kt` |
| Organic outlines | `composeApp/.../ui/map/CoverageOutline.kt` |
| Minimap | `composeApp/.../ui/components/ScanMinimapOverlay.kt` |
| Flashlight beam | `composeApp/.../ui/map/VisibilityBeam.kt` |
| World-map layers | `composeApp/.../ui/map/GeoJsonLayers.kt`, `GccMap.ios.kt` |
| Zoom-bias invariant | `composeApp/.../ui/map/MapStyles.kt::mapOverlayZoomBias` |
| Indoor gate | `composeApp/.../platform/OutdoorGate.kt`, `ui/map/BuildingData.kt`, `platform/BuildingFootprintLoader.kt` |
| AR bridge | `composeApp/src/iosMain/.../platform/ArNativeSurface.kt`, `iosApp/iosApp/GccArNative.swift`, `platform/ArEnvScan.kt` |
| Coverage queues | `composeApp/.../platform/CoverageEventQueue.kt` |
| Server persistence | `site/src/lib/scan-coverage.ts`, `site/src/app/api/scans/coverage/*`, `site/src/app/api/map/*` |
| Cell keying / conventions | `shared/.../geo/GridCell.kt`, `site/src/lib/world-observations.ts`, `docs/CONVENTIONS.md` |
| Points economy | `site/src/lib/points-config.ts` |
