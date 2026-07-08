# Cell-Health Choropleth (far-zoom) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development.

**Goal:** Replace the density-interpolated point heatmap at far zoom with a per-cell health choropleth — each cell colored by its OWN boundary's health (litter observations + weights, coverage state, staleness), aggregating 10m ledger cells into zoom-scaled tiles. Close zoom keeps footprints (P8). Remove the point-heatmap layers once the choropleth lands. Spec: v2 Pillar 8 amendment (merged #119).

## Global Constraints
- Gates: `:composeApp:desktopTest :composeApp:linkDebugFrameworkIosSimulatorArm64`; web/server `cd site && npx tsc --noEmit`; xcodebuild at sweep only if iosMain map code changed.
- No prod db commands.
- The coverage/observation data already flows: `GET /api/map/coverage` returns per-cell {lat,lng,bestQuality,lastScannedAt,priority}; `GET /api/map/observations` returns litter {lat,lng,state,itemType,weight}. The choropleth is a RENDER change fed by data already fetched in MapFullScreen (coverageCells + mapObservations).
- Heat = FAR zoom only (below the footprint fade-in ~14.2). Above that, P8 footprints own the story — do NOT touch them.
- Health scale (document + unit test the pure function): per aggregated tile, health ∈ [0,1] where 1=clean-scanned, 0=heavy-litter/never-scanned. Inputs: has-litter (weight-weighted) pushes toward red; scanned-clean pushes green; never-scanned = neutral grey veil (matches fog language); stale = amber. This is the SAME visual language as the coverage tiles, extended to far zoom as aggregated cells.

### Task 1: Aggregation function (shared, pure, tested)
**Files:** new `composeApp/src/commonMain/kotlin/com/gcc/app/ui/map/CellHealthChoropleth.kt` (+ commonTest)
- [ ] `aggregateCellHealth(coverageCells, observations, tileMeters): List<HealthTile>` where HealthTile = {centerLat, centerLng, health: Double, dominant: HealthState}. Snap each 10m ledger cell + observation into tileMeters-sized bins (tileMeters scales with zoom — pass it in); per bin compute health from the constituents (litter weight vs clean-coverage vs stale vs unscanned). Pure, deterministic, unit-tested: single clean cell → green tile; cell with heavy litter → red; mixed → weighted; empty bin excluded.
- [ ] Commit: `feat(choropleth): pure cell-health aggregation function`

### Task 2: GeoJSON + mobile render
**Files:** `composeApp/.../ui/map/GeoJsonLayers.kt` (add buildChoroplethGeoJson), `GccMap.ios.kt` (replace spot-density heat FillLayers with the choropleth layer, far-zoom gated), `GccMap.kt` expect if signature changes
- [ ] buildChoroplethGeoJson(tiles) → one FillLayer with data-driven color (interpolate health→green/amber/red/grey) at far zoom (fade OUT at the footprint fade-in zoom so the two never both show). REMOVE the old point-density spot heat layers (the ones keyed on `spots`/visibleSpots density). Keep the affected-block heat if it's a distinct admin layer — verify; only the litter-density heat is replaced.
- [ ] MapFullScreen: feed coverageCells + mapObservations into the aggregation (choose tileMeters from currentMapZoom). No new fetch — reuse what's loaded.
- [ ] Commit: `feat(choropleth): mobile far-zoom cell-health tiles replace point heat`

### Task 3: Web parity
**Files:** `site/src/components/map/CombinedMap.tsx` (+ any shared heat helper)
- [ ] Same choropleth on the website far zoom (observations + coverage already available to the web map); remove the web point-heat layer. tsc + visual-sanity note.
- [ ] Commit: `feat(choropleth): web far-zoom cell-health tiles`

### Task 4: Sweep + PR
- [ ] Merge origin/main, full gates (+ xcodebuild if iosMain touched), /simplify pass, push, `gh pr create` --base main NOT draft. Body: health scale, zoom hand-off (choropleth far ↔ footprints close), what point-heat layers were removed, deferred (server-side pre-aggregation if client aggregation is heavy on large viewports — note the cell count bound).
