# Scan Coverage Quality (Grazed vs Close) + Indoor Credit Fixes

**Date:** 2026-07-06
**Status:** Approved by Blake (design conversation, this date)
**Motivation:** An indoor scan credited 3 server geotiles as scanned. Root causes traced:
(1) the outdoor gate can transiently reach OUTDOOR before flipping INDOOR, and crediting
runs during that window; (2) the 10s INDOOR-flip purge drops path/items/photos but NOT
credited coverage cells or already-queued coverage events; (3) AR crediting uses a blind
15m × 63° frustum (`CoverageTracker.updateWithFrustum`, rangeMeters=15.0) that credits
through walls, and the server's `cell_coverage_summary` is append-only with no retract
path. Separately, Blake wants the scan UX to guide users toward *complete, high-quality*
environment scans the way a 3D-scanning app does — which requires distinguishing "camera
grazed over this area at distance" from "close-up detailed scan".

## Decisions made (with Blake)

1. **Quality tier is full-stack** — client tracker → wire model → DB → both maps.
2. **Mechanism: raycast fan** — credit where rays *land*, not a geometric cone.
3. **Retraction: client purge + server retract endpoint** + one-off cleanup of the
   3 bad geotiles.
4. **Points: split award** — grazed pays a fraction, upgrading to close pays the
   remainder, straight-to-close pays full.

## 1. Client crediting model (`CoverageTracker`)

File: `composeApp/src/commonMain/kotlin/com/gcc/app/platform/CoverageTracker.kt`

- Replace `_scannedCells: Set<Pair<Int,Int>>` with `Map<Cell, CellCredit>` where
  `CellCredit(quality: CoverageQuality, creditedAtMs: Long)` and
  `enum CoverageQuality { GRAZED, CLOSE }`.
- **Monotonic upgrades only:** GRAZED → CLOSE allowed; never downgrade. `creditedAtMs`
  updates on upgrade (needed for purge windows).
- **New AR credit path `updateWithRaycastHits(hits)`:** replaces `updateWithFrustum`
  on the AR path (the 15m frustum is deleted from AR crediting). Each ~1s tick, the
  overlay casts a **5×3 fan of rays** across the camera FOV via the existing
  `GccArNative.raycast` bridge (`ArEnvScan.kt` `raycastImage` → `RayHit(x,y,z,distance)`,
  distance already computed at `ArEnvScan.kt:491`). Each hit's AR-local position converts
  to geo via the existing geo-ref pairing math (inverse of `latLngToLocal`), and credits
  **the cell the ray landed in**:
  - hit distance ≤ `CLOSE_RANGE_METERS` (4.0) → CLOSE
  - hit distance ≤ `GRAZE_RANGE_METERS` (10.0) → GRAZED
  - hit beyond 10m, or no hit (sky/void) → **no credit**
  - Constants live beside `cellSizeMeters`, tunable, pinned by tests.
- **Indoor overreach dies structurally:** rays terminate on real surfaces, so a wall
  2m away means nothing beyond it can credit. The credited footprint is *measured*,
  not a tuned radius; 10m is a ceiling, not the shape.
- **Fallbacks:** non-AR devices and degraded tracking keep `updatePosition` (5m GPS
  disk) which can only ever credit GRAZED. No geo-ref pairing yet → GPS-disk fallback.
  All rays miss → no credit that tick (correct: pointing at sky scans nothing).
- **Route-lap completion unchanged in difficulty:** `coveragePercent` (and the 95%
  lap auto-advance) counts EITHER tier as covered. New `closePercent` reports the
  CLOSE-tier fraction separately.
- `seedScannedCells` (ledger-fresh fog exemption) carries the server-provided quality
  so seeded cells shade correctly on the minimap.

## 2. Indoor purge, completed

Files: `OutdoorGate.kt`, `RouteScanViewModel.kt` (~:1035), `SpotScreen.kt` (~:393),
`SpotArScreen.kt` (~:180), `CoverageEventQueue.kt`

On INDOOR flip (existing `PURGE_WINDOW_MS = 10_000` cutoff), in addition to the
existing path/items/photos purge:

- `CoverageTracker.purgeSince(cutoffMs)` — remove cells whose `creditedAtMs >= cutoff`.
  (A cell upgraded in the window but originally GRAZED before the window reverts to
  GRAZED rather than disappearing — track prior quality on upgrade, or accept full
  removal; spec choice: **revert to prior state**, keep a one-deep prior on upgrade.)
- `CoverageEventQueue.purgeSince(cutoffMs)` — drop still-pending persisted events with
  `capturedAt >= cutoff` and re-persist the queue.
- For events already flushed in the window: enqueue a **retraction** —
  `POST /api/scans/coverage/retract` `{deviceId, cellIds, fromMs, toMs}`. Retractions
  queue offline exactly like coverage events (same persistence pattern, flushed by the
  same cadence) so an offline INDOOR flip still retracts later.

## 3. Wire model + server

Files: shared wire models (`ScanCoverageEventRequest`), `site/src/app/api/scans/coverage/route.ts`,
`site/src/lib/scan-coverage.ts`, drizzle schema + migration.

- `ScanCoverageEventRequest` gains `quality: "grazed" | "close"`. **Missing/old-client
  value defaults to `grazed`** — deployed builds credit via the blind 15m cone, which is
  exactly what grazed means; legacy data must not inflate to close.
- Schema: `scan_coverage_events` + `quality` column (text, default 'grazed');
  `cell_coverage_summary` + `best_quality` (text, default 'grazed') and nullable
  `last_close_scanned_at`. Summary upsert sets `best_quality='close'` when any close
  event lands and bumps `last_close_scanned_at`.
- **Retract endpoint** `POST /api/scans/coverage/retract`: validates deviceId + window
  (bound window length ≤ e.g. 60s to keep it purge-shaped, not a bulk-delete API),
  deletes that device's `scan_coverage_events` rows for those cells in `[fromMs, toMs]`,
  **recomputes** each touched `cell_coverage_summary` from surviving events (deleting
  the row when none remain), and claws back `points_ledger` rows those events awarded.
- **Points split:** grazed `new_area` pays a fraction (ballpark 40%, exact numbers set
  in the implementation plan alongside existing 10/5/0 rates); a grazed→close upgrade
  event pays the remainder; straight-to-close pays full. Categories stay auditable
  (e.g. `new_area_grazed`, `close_upgrade`).
- `GET /api/map/coverage` returns per-cell `quality`; freshness derivation (24h stale)
  unchanged.
- **One-off cleanup:** script/SQL to delete the 3 indoor geotiles' events and summaries
  (identified by cellId near Blake's scan location/time), run once after deploy.

## 4. Minimap, AR, and guidance (the 3D-scanner feel)

Files: `ScanMinimapOverlay.kt`, `SpotScreen.kt` (~:585-605), AR overlays
(`ArEnvScanOverlay`/`ArRouteOverlay` + `GccArNative.swift`), `EnvScanGuidance.kt`

- **Minimap two shades:** session cells split into two draw passes — GRAZED at
  GccGreen α≈0.25 (light shade), CLOSE at the existing α0.60 (vivid). Server-fresh
  cells get the same two-shade treatment from the new quality field. Draw order slots
  between the existing stale (α0.45 amber) and session passes; `drawCell` already takes
  arbitrary color/alpha. Veil/punch-out logic unchanged.
- **AR ground quads mirror it:** credited-cell quads render faint for GRAZED, vivid for
  CLOSE (tier flag through `updateScannedCells` → Swift entity alpha).
- **Two-phase guidance:**
  - *Phase 1 (cover):* existing frontier ring/arrow steers to un-grazed cells —
    unchanged behavior.
  - *Phase 2 (detail):* once grazed coverage is high (threshold set in plan, e.g.
    corridor grazed ≥ 80%), `pickFrontier` retargets the nearest GRAZED-but-not-CLOSE
    cluster; caption changes to "move closer to detail-scan this area". Reuses the
    existing ring/arrow and the existing >2.2m move-closer reticle so cues agree.
- Fog (`updateFog`) keeps meaning "never credited at all" — grazed cells are out of
  fog but visibly lighter, which IS the guidance signal.

## 5. Testing

- `CoverageTracker` unit tests in **composeApp commonTest** (shared's commonTest is
  broken on all targets — known): tier credit thresholds, monotonic upgrade,
  purge-window revert-to-prior, closePercent vs coveragePercent, GPS path caps at
  GRAZED, frozen CLOSE/GRAZE range constants.
- `CoverageEventQueue.purgeSince` persistence test (pending events dropped survive
  restart) + retraction queue offline test.
- Server: retract endpoint recompute test (summary recomputed/deleted, points clawed
  back, window-length bound enforced), wire backward-compat test (missing quality →
  grazed), quality upsert test.
- The frozen `gridCell` test stays untouched — cell keys must remain byte-equal
  (R2 raw/{cellId}/ paths depend on them).

## 6. Explicitly deferred

- LiDAR `sceneDepth` per-pixel coverage (buffer is enabled but unread) — the v2
  upgrade path once raycast UX proves out.
- DGX-pipeline consumption of the quality field.
- Any change to gate-flip evidence rules themselves (OutdoorGate v4 logic untouched;
  this spec makes the *consequences* of a late flip recoverable).
