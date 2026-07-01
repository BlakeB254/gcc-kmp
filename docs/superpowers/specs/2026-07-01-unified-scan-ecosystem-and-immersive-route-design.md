# Unified Scan Ecosystem & Immersive Route Experience — Design

**Date:** 2026-07-01 · **Author:** Blake (with Claude) · **Status:** Design — pending approval

**Relationship to canon:** Serves `docs/feature-spec-2026-05.md` §A (canon) and extends
`docs/PRD_route-queue-and-scans.md` (currently only on the unmerged
`feat/route-offer-notifications-live` branch — see §1). This doc is the spec for four
initiatives being planned together because they share data and code paths:

1. Ship the existing (unmerged) route-queue/scan/notification work to `main` and TestFlight.
2. A unified **world observation model** — spot reports and route-scan hotspots feed one
   data model; newer scans supersede older ones per physical location; full chronological
   history is retained; the map renders scan-derived scatter instead of a flat dot.
3. **Private-property exclusion zones** — admin-drawn no-go polygons so workers are never
   instructed to clean behind a gate or fence.
4. **Immersive AR route experience** — once a worker is inside their route boundary and
   taps "Begin Route," a full-screen AR view with directional guidance, boundary
   visualization, and real-time fog-of-war reveal during lap 1.

**Sequencing note:** the alpha (two 10-block North Lawndale sessions) was originally
targeted for +4 days. Per explicit decision, initiative #2 (world model) and #4 (AR
experience) now take priority over that date — the alpha can slip. §7 lays out the
phasing and what can ship independently of what.

---

## 1. Initiative 1 — Ship the existing branch (low risk, do first, doesn't block anything)

**Finding:** the branch `feat/route-offer-notifications-live` (worktree
`notif-cron-deeplink`, tip `13ff706`) shares a merge-base with `main` that is **exactly
main's current tip** (`6541834`). That means `main` is a strict ancestor of the branch —
this is a **pure fast-forward**, not a three-way merge. Confirmed via full diffstat: all
25 changed files are net-new API routes (`route-queue.ts`, `route-queue-notifications.ts`,
APNs push, cron endpoint), the governance/PRD docs, and `.env.example`/`vercel.json`
config. **Zero touches to `BaseMap.tsx` or `CombinedMap.tsx`** — the live heatmap
(`SPOT_HEATMAP_PAINT`, the dots↔heatmap zoom crossfade) is untouched by this merge.

**Action:** fast-forward `main` to the branch tip (or merge with `--ff-only`), push, then
run the existing pre-session checklist from `docs/governance/gap-analysis-queue-onboarding.md`:
backfill `signupRankings*` for the 5 verified North Lawndale workers, confirm the 10
published routes for the target zips (60623/60624), remove test route `NL-TEST01`,
and finish the hybrid admin-override UI for scan pass/fail (currently ⚠️ partial per
`PRD-master.md` §4). Then promote the build via `cdx-mobile build ios --sync` on the
MacBook to get route-queue + scans onto a TestFlight build for the first time (last
TestFlight build, 88, predates all of this work — this is almost certainly why "we
can't get the driven routes yet with environment scans" felt true even though the
feature is built).

This step is pure upside and doesn't need to wait on anything below.

---

## 2. Initiative 2 — Unified world observation model

### 2.1 Current state

Two separate systems capture the same underlying thing (a piece of trash observed
somewhere) with two separate schemas and two separate APIs:

- **`scanHotspots`** (`site/src/db/schema.ts:1440`) — tied to a `routeScans` session during
  the scheduled two-lap cleaning flow. Flat columns: `lat`, `lng`, `trashTypes`,
  `severity`, `estimatedVolumeLiters/Lbs`, `confidence`, `beforePhotoUrl`/`afterPhotoUrl`,
  `arAnchorData`, `depthData`, `detectedAt`/`cleanedAt`, `status`.
- **`communityReports`** (`site/src/db/schema.ts:1118`) — the ad-hoc "spot report" flow.
  Richer polymorphic shape: reporter identity, `type`, geographic categorization (ward,
  census tract, community area), `mediaIds`, `depthAnalysis` (LiDAR — already captured!),
  `onDeviceAiResult` (on-device trash classification — already captured!),
  `captureAttestation`, `tags`, moderation/verification workflow (`status`, `reviewedAt/By`).
- Only `communityReports` renders on the map today, as a single `circle-color` dot
  (`CombinedMap.tsx:222`, `SPOT_COLORS.MARKER`).

So the *capture* data model for spot reports already supports scan-quality input
(depth, on-device AI) — it's the *map rendering* and the *lack of a shared observation
concept* that's missing, not the underlying capture capability.

### 2.2 Approach: additive canonical layer (Option C)

Considered and rejected:
- **Full replacement** (retire both tables, migrate everything into one new table) —
  cleanest long-term, but rips out the working moderation/peer-verification/payout logic
  wired to `communityReports` this close to launch. Too risky.
- **View-only unification** (leave both tables alone, UNION them at query time for the
  map) — fastest, but weaker long-term: item-level scatter fidelity for spot-reports
  would depend on parsing loosely-structured `onDeviceAiResult`/`tags` on every query,
  and there'd be two systems of record forever with no clean place to hang supersession
  history.

**Chosen approach:** add a new `worldObservations` table as the map/digital-twin layer,
populated by a shared write helper called from both existing write paths. `scanHotspots`
and `communityReports` keep owning their own domain workflows (payout, peer verification,
moderation, admin review) — nothing about those changes. `worldObservations` only needs
the observational core: what was seen, where, when, and its current state.

### 2.3 Schema

```
worldObservations
  id                 serial PK
  scanHotspotId      integer NULL   -- FK to scanHotspots.id, when sourced from a route scan
  communityReportId  integer NULL   -- FK to communityReports.id, when sourced from a spot report
  sourceType         varchar(20)    -- 'route_scan' | 'spot_report'
  lat                doublePrecision
  lng                doublePrecision
  locationCellId     varchar(32)    -- grid-cell key, see 2.4 — indexed
  itemType           varchar(50)    -- 'tire' | 'couch' | 'ebike' | 'tree_branch' | 'glass' | 'general_litter' | ...
  state              varchar(10)    -- 'dirty' | 'clean'
  severity           integer NULL
  estimatedVolumeLiters  doublePrecision NULL
  estimatedWeightLbs     doublePrecision NULL
  confidence         doublePrecision NULL
  mediaUrl           varchar(500) NULL
  depthData          text NULL      -- carried through when available
  arAnchorData       text NULL      -- carried through when available
  capturedAt         timestamp      -- when the underlying scan/report actually happened
  isCurrent          boolean default true   -- flips false when superseded
  excludedPrivateProperty boolean default false  -- see §3
  createdAt          timestamp defaultNow
```

Indexes: `(locationCellId, itemType, isCurrent)` for the map's "current state" query;
`(scanHotspotId)`, `(communityReportId)` for joins back to the source record.

### 2.4 Location cell & supersession algorithm

No PostGIS is in use on this Neon database today (confirmed — geofencing elsewhere uses
hand-rolled `pointInPolygon`/haversine, not `ST_DWithin`), so grid-cell grouping is done
in application code using the `METERS_PER_DEG_LAT`/`METERS_PER_DEG_LNG` constants already
in `site/src/lib/geo-utils.ts`. Cell size: ~10m, matching the existing 15m checkpoint
geofence scale already used elsewhere in the schema — fine enough to distinguish "the
couch on this side of the block" from "the tire on the other side," coarse enough to
tolerate normal GPS/AR-anchor drift between two visits to the same spot.

```
function recordWorldObservation(input):
  cellId = gridCell(input.lat, input.lng, cellSizeMeters=10)

  # find whatever is currently "current" for this exact (cell, itemType)
  prior = SELECT * FROM worldObservations
          WHERE locationCellId = cellId AND itemType = input.itemType AND isCurrent = true

  if input.state == 'clean':
    # a "clean" scan of a cell supersedes ALL currently-dirty item types in that cell,
    # not just a matching itemType — a clean re-scan means "nothing dirty found here"
    priorDirty = SELECT * FROM worldObservations
                 WHERE locationCellId = cellId AND state = 'dirty' AND isCurrent = true
    UPDATE worldObservations SET isCurrent = false WHERE id IN priorDirty.ids
    INSERT new row (itemType = null, state = 'clean', isCurrent = true, ...)
  else:
    if prior exists:
      UPDATE worldObservations SET isCurrent = false WHERE id = prior.id
    INSERT new row (state = 'dirty', isCurrent = true, ...)

  return newRow
```

Nothing is ever deleted or overwritten in place — every insert is a new immutable row.
"Current state" = `WHERE isCurrent = true`. "Cleanliness history for a block" = all rows
for that block's cells ordered by `capturedAt`, which is exactly the chronological
dirty→clean→dirty timeline requested (and feeds the canon §A.8 "compounding data" /
digital-twin story directly — this table *is* the digital twin's observational spine).

### 2.5 Write-path integration

New shared function `site/src/lib/world-observations.ts` → `recordWorldObservation(input)`,
called from:

- `POST /api/scans/[id]/hotspots` — on hotspot detection during SCOUTING (`state='dirty'`,
  `itemType` from `trashTypes[0]` or one row per tag if multiple).
- `PATCH /api/scans/[id]/hotspots/[hid]` — when a hotspot is marked cleaned during CLEANING
  (`state='clean'`).
- `POST /api/spots` — on spot-report creation (`state='dirty'`; explode `onDeviceAiResult`
  into one row per distinct detected item when the AI result differentiates them, else one
  row using `tags[0]` or `'general_litter'`).
- `POST /api/spots/[id]/volunteer/[cleanupId]/complete` — when a volunteer cleanup is
  verified complete (`state='clean'`).

One-time backfill script `scripts/backfill-world-observations.ts`: walk existing
`scanHotspots` and `communityReports` rows in chronological order, call
`recordWorldObservation` for each, so history exists from day one instead of only going
forward.

### 2.6 Map rendering — reuses the existing heatmap, doesn't replace it

`CombinedMap.tsx` already implements exactly the "heatmap at wide zoom → dots at close
zoom" crossfade requested (`SPOT_HEATMAP_PAINT`, `SPOT_ZOOM.DOTS_MIN`/`HEATMAP_MIN/MAX/FADE_OUT`,
lines ~142, 646–705). This is good, working code — the plan is to **repoint its GeoJSON
source at the new `worldObservations` view** (current, non-excluded rows, unioning both
source types) rather than rebuild it, and **replace the close-zoom circle-dot layer with
a symbol layer keyed by `itemType`** (one icon per detected item at its real point — tire
icon, couch icon, branch icon, etc.), leaving the wide-zoom heatmap layer exactly as is.
That satisfies "zoom-dependent, both" with no new map library and no risk to the existing,
already-tuned heatmap.

New endpoint: `GET /api/map/observations?bbox=...&zoom=...` serving the unified,
zoom-appropriate observation set to the map.

New endpoint: `GET /api/blocks/[id]/observation-history` — full timeline per block for
future cleanliness-trend / analytics surfaces (not building the UI for this now, just
making sure the data model doesn't foreclose it).

---

## 3. Initiative 3 — Private-property exclusion zones

**Current state confirmed:** no polygon-based exclusion exists anywhere in the codebase.
Geofencing is exclusively circular-radius distance checks (checkpoints default 15m).
`blocks.locationBoundary` is a GeoJSON polygon used for display only, not for exclusion.

**Design:** new table `blockExclusionZones` (`blockId` FK, `polygon` jsonb GeoJSON ring,
`label` e.g. "gated yard — 123 Main St", `createdByAdminId`, `createdAt`). Admin draws
zones using a new `ExclusionZoneDrawer.tsx` component that's a direct copy of the existing
`RouteDrawer.tsx` click-to-add-point pattern (already proven, already using MapLibre +
`react-map-gl/maplibre`) — the only difference is the polygon closes back to its first
point instead of being buffered into a block-width strip via `lineStringToBlockPolygon`.
`pointInPolygon` (`geo-utils.ts:112`) — already implemented, already tested — does the
enforcement check.

**Enforcement point:** inside `recordWorldObservation()` (§2.5), after computing lat/lng,
check the point against all of the block's exclusion zones. If inside one, set
`excludedPrivateProperty = true` on the row instead of dropping it (keep it for admin
audit/debugging) — excluded rows are filtered out of both the map's current-state query
and the CLEANING-phase lap-2 instruction list the app gives the worker. This is the single
enforcement point that both the map and the app's clean-instructions consume, so there's
no way for a client bug to route a worker into a zone the server has excluded.

Client-side pre-filtering (fetch the block's zones during SCOUTING and gray out capture
prompts before they're even sent) is a nice UX polish for a later pass — not required for
correctness, since server-side filtering at write-time is authoritative.

---

## 4. Initiative 4 — Immersive AR route experience

**Trigger:** GPS confirms the worker is inside the route's geofence boundary and they tap
"Begin Route" — the app switches from its current map-centric UI into a full-screen AR
camera view.

**Existing foundation to build on** (not duplicate) — confirmed by direct code inspection:
- `ArScanner.kt` (iosMain, 113 lines) wraps `ARSession`/`ARWorldTrackingConfiguration`
  (scene-depth semantics, gravity+heading world alignment) and exposes `startSession()`,
  `stopSession()`, `placeAnchor(lat,lng)`, `getCameraFovDegrees()` (hardcoded 63°), and
  `getArHeading()` (derived from the camera transform matrix) to `commonMain`. **It does
  zero on-screen rendering today** — no SceneKit, no RealityKit, no ARSCNView, no delegate,
  no anchor list exposed back out. It is a headless data source only. This means the
  boundary-wall and fog-of-war visuals are a **first-time AR rendering integration**, not
  an enhancement of existing AR visuals — size the plan accordingly.
- `CoverageTracker.kt` (commonMain, 277 lines) already maintains a **lat/lng grid**
  (3m cells, restricted to a 15m-wide corridor around the route's checkpoints) with a
  GPS-tier update (`updatePosition`, 5m scan radius) and an AR-tier update
  (`updateWithFrustum`, 15m range, FOV-gated by heading) — exposing `scannedCells`
  (`StateFlow<Set<Pair<Int,Int>>>`) and `coveragePercent`. This data shape is already
  exactly fog-of-war-ready; today it only feeds a numeric coverage chip and
  `AiDirectionEngine` — nothing renders the grid visually. The new AR view consumes
  `scannedCells` directly rather than inventing new tracking.
- `MapAnimations.kt`'s `drawGpsHeadingCone()` is a flat-canvas 2D drawing function, and
  it is **only invoked from the desktop map** (`GccMap.desktop.kt`). iOS and Android
  `GccMap` variants accept a `gpsHeading` parameter but never call this function — heading
  on those platforms likely renders via a native MapLibre puck-bearing layer instead
  (unconfirmed — re-verify before assuming iOS already shows a heading cone anywhere).
  Either way, this is 2D top-down map decoration; there is no camera-space/AR equivalent
  today, on any platform.
- Today, SCOUTING is a full-screen 2D map (`ScoutScreen.kt` → `GccMap`) with a capture FAB
  that opens a **modal** camera dialog (`SpotScanCapture`/`CameraCapture`) using a plain
  `AVCaptureVideoPreviewLayer` session — entirely separate from, and unconnected to, the
  invisible `ARSession` in `ArScanner.kt`. The "Begin Route" AR mode described below is
  therefore a **new full-screen surface**, not a retrofit of an existing AR camera screen.
- **No boundary/polygon containment check exists client-side at all.** Route/service-area
  polygon geometry exists in the shared models (`Route.geometry`, `ServiceArea.boundary`)
  but `ServiceArea.boundary` is never fetched or referenced anywhere in
  `RouteScanViewModel` — all existing geofence logic (`GeofenceVerification.kt`) is
  pairwise point-distance only (checkpoint/photo proximity), not polygon containment. The
  "stepped outside your assignment" check needs both new wiring (fetch the boundary) and
  new logic (a Kotlin point-in-polygon equivalent to the one already used server-side in
  `geo-utils.ts`).

**New AR-view components:**

1. **Directional guidance** — an on-screen indicator (arrow/chevron, AR-anchored or
   screen-space) pointing toward the nearest un-covered frontier of the route, computed
   from the worker's live heading vs. bearing to the nearest uncovered cell in
   `CoverageTracker`'s existing grid.
2. **Boundary visualization** — project the route's polygon boundary into AR world-space
   as a translucent wall/fence rendered along whichever edge the camera is currently
   facing; when the camera points outside the assignment, the boundary renders with a
   clear "outside your area" treatment (color/opacity shift) so a worker never has to
   guess whether something is in scope.
3. **Fog-of-war reveal** — render `CoverageTracker`'s existing per-cell coverage grid as
   an AR ground-plane overlay: unscanned cells shown as translucent fog, scanned cells
   revealed/cleared, updating live as the worker walks lap 1. This is the same coverage
   data already being computed today for a numeric percentage — the new work is a visual
   layer on top of data that already exists, not a new tracking system.
4. **Phase-aware reuse** — during CLEANING (lap 2), the same AR view re-purposes the fog
   overlay to highlight only the confirmed hotspots awaiting a return visit, layering on
   top of the already-built `ProximityAlertCard.kt` proximity alerts.

**Open implementation question, not yet resolved:** confirmed `ArScanner.kt` has no
on-screen rendering today (§ above), so this is a first-time integration decision —
RealityKit vs. SceneKit/`ARSCNView`, and whether the overlay is driven via Kotlin/Native
cinterop calls into the chosen framework directly, or via a thin Swift-side
delegate/view that Kotlin feeds through the existing `ArScanner` data callbacks (heading,
anchors, `scannedCells`). RealityKit is the more modern, better-supported starting point
for translucent world-anchored planes (the boundary wall, the fog-of-war ground overlay)
and is the likely default recommendation, but the concrete choice — and how much of it is
reasonable to hand-author from the DGX vs. needing MacBook/Xcode iteration — gets decided
during implementation planning, not here.

**Build/test constraint:** this is native iOS rendering work. Source can be authored on
the DGX (composeApp is Kotlin, edited like any other code), but building and on-device
testing only happens on the MacBook via `cdx-mobile build ios --sync`. Expect an
iteration loop across both machines for this initiative specifically.

---

## 5. Documentation consolidation

The governance doc set (`docs/governance/00-INDEX.md`, `PRD-master.md`,
`ARCHITECTURE-and-source-of-truth.md`, `API-reference-production.md`, `app-map-mobile.md`,
`sitemap-website.md`, `gap-analysis-queue-onboarding.md`) plus `PRD_route-queue-and-scans.md`,
`Test-plan-cleaning-sequence.md`, and `TestFlight-runbook-route-queue-scans.md` currently
exist **only** on the unmerged branch. Initiative 1's fast-forward merge brings all of
them onto `main` for free. After that:

- Update `PRD-master.md`'s feature-status table (§4) and roadmap (§6) to add the world
  observation model, exclusion zones, and AR route experience as new rows/phases.
- Add a new PRD doc per initiative under `docs/` following the existing naming convention
  (`PRD_world-observations-and-map.md`, `PRD_private-property-exclusion.md`,
  `PRD_immersive-ar-route.md`) once each moves from design to implementation.
- Keep `docs/governance/00-INDEX.md` as the single entry point — this becomes the
  NotebookLM upload target: one indexed, dated, internally-consistent set instead of
  scattered specs across `docs/plans/`, `docs/superpowers/`, and a branch nobody's on.

---

## 6. Open decisions carried forward (not resolved by this design)

- Exact AR rendering mechanism (SceneKit vs. RealityKit, cinterop vs. Swift delegate) —
  resolved during implementation planning, not here.
- `itemType` taxonomy for `worldObservations` — should reuse whatever vocabulary
  `trashTypes`/`onDeviceAiResult` already produces; exact enum values confirmed during
  implementation.
- Consent / bystander-privacy policy for environment scans — already flagged as open in
  `PRD_route-queue-and-scans.md` §8; becomes more relevant as scanning becomes more
  visible/gamified via the AR view and unified map. Still unresolved; flagging again here.
- Icon assets for per-item-type map markers — visual design work (candidate for the
  `nanobanana`/design-first pipeline), out of scope for this architecture design.
- Hybrid scan pass/fail admin-override UI, signup-ranking backfill, admin 2FA — pre-existing
  gaps from `PRD_route-queue-and-scans.md` §8, unaffected by this design, still need closing
  before the real 10-block sessions run regardless of AR/world-model timing.

---

## 7. Phasing

- **Phase 0 (immediate, no dependency):** fast-forward merge to `main` (§1). Pure upside,
  doesn't block or get blocked by anything else here.
- **Phase 1:** world observation model — schema, backfill, write-path integration, map
  rendering swap (§2). Alpha timing may extend for this.
- **Phase 2 (built on Phase 1's plumbing):** exclusion zones (§3) — small relative to
  Phase 1, shares the same write-path enforcement point.
- **Phase 3 (iOS, DGX+MacBook loop):** immersive AR route experience (§4) — highest
  uncertainty, most likely determinant of how far the alpha date slips.
- **Phase 4 (continuous):** documentation updates, finalized once Phases 1–3 land.

## 8. Success metrics

- Every completed block has a full, queryable observation history (dirty→clean→dirty)
  joined to block/session/worker — not just a final photo.
- Zero hotspots inside an admin-flagged exclusion zone ever appear in a worker's
  lap-2 clean instructions.
- Map at close zoom shows individually-placed, correctly-typed item icons for every
  current observation; at wide zoom, the existing heatmap crossfade is unchanged in
  behavior, only in data source.
- A worker starting a route sees, within the AR view, real-time visual feedback on
  what's covered vs. not, and a clear signal the moment they point the camera outside
  their assigned boundary.
