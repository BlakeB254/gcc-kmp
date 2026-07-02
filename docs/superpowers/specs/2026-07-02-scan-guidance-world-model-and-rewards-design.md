# Environment Scan Guidance, World Model & Reward Economy — Design

**Date:** 2026-07-02 · **Author:** Blake (with Claude) · **Status:** Design — pending approval

**Relationship to canon:** builds directly on `docs/superpowers/specs/2026-07-01-unified-scan-ecosystem-and-immersive-route-design.md`
(shipped to `main` via PR #6, 2026-07-01, now confirmed present in this checkout).
Specifically:

- Finishes that spec's Initiative 4 "open implementation question." AR rendering is
  already resolved in shipped code — `ArRouteOverlay.kt` (iosMain, 123 lines) is a
  SceneKit/ARKit `ARSCNView` implementation rendering fog-of-war and a boundary wall.
  But the **directional-arrow guidance is explicitly stubbed** — the arrow-node creation
  and lat/lng→AR-local-space coordinate transform are marked `DEFERRED` in code, not
  built. This spec finishes that.
- Extends that spec's Initiative 2 (`worldObservations` unified model) from a
  litter-only observation log into a multi-domain, multi-device digital-twin spine,
  and adds the missing "was this area physically scanned" coverage ledger that
  `worldObservations` does not provide (it only records litter-item state, not
  scan-coverage state).
- Extends `docs/superpowers/specs/2026-06-30-same-day-payouts-design.md`'s payout
  state machine (`pending_review` → admin approve/deny → Stripe transfer → webhook)
  with reward-feedback UX at each real transition, including wiring the two
  currently-unused push templates (`jobApproved`, `payoutSent`) that spec left dead.

This spec covers seven phases. They are being planned together because they share one
architectural spine (the chronological, multi-device world-event model in Phase 0) even
though they ship and get implemented incrementally — see §10 for sequencing.

---

## 0. Phase 0 — Multi-device chronological world-event model

### 0.1 What's missing today

`worldObservations` (schema.ts, shipped 2026-07-01) already does one thing very well:
an append-only, never-mutated log of **litter-item state** per ~10m grid cell, with
correct dirty→clean supersession. It is explicitly described in its own design doc as
"the digital twin's observational spine." But it answers only one question: *is there
known litter here, and what kind?* It cannot answer *was this location ever physically
scanned*, or *when was it last scanned*, independent of whether litter was found. A
patch of clean sidewalk that's never been visited and a patch of clean sidewalk that was
scanned yesterday and confirmed clean look identical in `worldObservations` — both have
zero current rows. That ambiguity is exactly what surfaced as a real user complaint in
this session ("an area shows as clean on the map that's dirty in real life") and blocks
never-scanned / stale-scan indication (Phase 4), distance-tracked points (Phase 5), and
real-time in-camera coverage guidance beyond the current session (Phase 1).

### 0.2 New table: `scanCoverageEvents`

Companion to `worldObservations`, not a replacement. Append-only, same architectural
pattern (event log, current-state derived by folding, nothing mutated in place):

```
scanCoverageEvents
  id                 serial PK
  deviceId           varchar(64)     -- stable per-install identifier
  platform           varchar(16)     -- 'ios' | 'android'
  workerId           integer NULL FK -- who, when authenticated
  locationCellId     varchar(32)     -- same 10m grid as worldObservations — indexed
  lat                doublePrecision
  lng                doublePrecision
  distanceMeters      doublePrecision  -- new corridor/area distance covered by this event
  scanMode           varchar(10)     -- 'route' | 'spot'
  deviceTier         varchar(16)     -- 'basic' | 'scanner' | 'pro_scanner'
  capturedAt         timestamp
  createdAt          timestamp defaultNow
```

Index: `(locationCellId, capturedAt)` — this is the query that answers "when was this
cell last scanned" and "has this cell ever been scanned" by folding max/min over
`capturedAt` per cell. A separate low-cardinality summary table
(`cellCoverageSummary`: `locationCellId` PK, `firstScannedAt`, `lastScannedAt`,
`totalDistanceMeters`, `scanCount`) is maintained as a materialized rollup, upserted on
every `scanCoverageEvents` insert, so reads (map tile coloring, points calculation, AR
guidance queries) never have to fold the full event log — only writes touch it, and
writes are cheap (single upsert).

**Multi-device requirement (explicit, per this session's direction):** every field on
`scanCoverageEvents` is platform-agnostic by design. iOS is the only *producer* built in
this spec (via the AR overlays in Phase 1), but the schema, the write endpoint, and the
summary-rollup logic carry no iOS-specific assumption anywhere. When Galaxy/Android AR
scan guidance is built (its own future spec, ARCore-based), it becomes a second producer
of the same event shape — the backend, map, and points system require no changes to
accept it.

### 0.3 `worldObservations` domain extension

Add `domain varchar(20) default 'litter'` to `worldObservations`, values
`'litter' | 'vegetation' | 'construction' | 'land_use'`. This is a schema-only change in
this spec — existing rows backfill to `'litter'`, existing write paths
(`recordWorldObservation`) are unchanged in behavior, and no new on-device or
server-side detection model is built for the three new domains here. The goal is
narrowly to stop the schema from foreclosing the "track construction progress / grass
cut or not / trees removed / lots repurposed" ambition described this session, following
the same pattern the shipped spec already used elsewhere (e.g. the
`observation-history` endpoint built "not for a UI now, just so the data model doesn't
foreclose it"). Each new domain's actual detection logic is its own future increment —
scoping four new CV/classification pipelines into this spec would be a different, much
larger project.

### 0.4 New endpoints

- `POST /api/scans/coverage` — batched `scanCoverageEvents` insert (client buffers and
  flushes periodically, same pattern as any offline-tolerant mobile write path).
- `GET /api/map/coverage?bbox=...` — per-cell summary (`neverScanned` / `fresh` /
  `stale`, `lastScannedAt`, `totalDistanceMeters`) for the map (Phase 4) and, once wired,
  the AR guidance engine (Phase 1 fast-follow).

---

## 1. Phase 1 — iOS AR scan-guidance overlay

### 1.1 Route mode: finish `ArRouteOverlay`, don't rebuild it

`ArRouteOverlay.kt` already renders fog-of-war (translucent SceneKit planes per
unscanned `CoverageTracker` cell, removed as cells clear) and a boundary wall (turns red
when the camera looks outside the route polygon). This spec implements the deferred
piece: a directional arrow node, computed from `AiDirectionEngine.bearingTo()` plus
`CoverageTracker.getUnscannedCenters()`, transformed from lat/lng into the AR session's
local coordinate space (the transform itself is the specific piece marked `DEFERRED` in
the current code and is this spec's concrete implementation task).

`AiDirectionBanner.kt`'s text-prompt UI is retired for AR-capable tiers once the arrow
ships — it remains the **only** guidance UI for `BASIC` tier devices (no ARKit), using
`AiDirectionEngine`'s existing GPS-tier positional prompts, unchanged.

### 1.2 Spot mode: new guidance, no existing scaffold

Object-relative "circle coverage" guidance for framing a single litter item from
multiple angles — no equivalent exists today (`SpotScanCapture.kt` is a flat capture UI
with no coverage/guidance layer). New `ObjectCoverageTracker.kt` (commonMain): angular
coverage analog to `CoverageTracker` — instead of grid cells along a corridor, it tracks
which angular sectors around a fixed point have been captured, at which distances, and
exposes the same shape of "uncovered centers" the arrow-rendering logic already
consumes. New `SpotArOverlay.kt` (iOS-first, Android/desktop stub — consistent with
existing `ArScanner`/`ArRouteOverlay` platform precedent) renders it through the same
arrow+text visual language as route mode.

Both modes share one `ScanMode` (`ROUTE` | `SPOT`) parameter on a common overlay
composable rather than two independently-built UIs — same rendering, animation, and AR
session plumbing; different guidance engine underneath.

### 1.3 Device tiering

- `PRO_SCANNER` (LiDAR): full depth-based fog-of-war + boundary + arrow, as already
  built for route mode, extended to spot mode.
- `SCANNER` (ARKit, no LiDAR): same overlay, coverage computed via
  `CoverageTracker.updateWithFrustum` (heading + FOV cone, no depth mesh required —
  already implemented, just not yet wired to a spot-mode consumer).
- `BASIC`: unchanged — `AiDirectionBanner` text prompts, no camera overlay.

### 1.4 Tracking-loss handling

New `ArSessionHealth.kt` (expect/actual), wrapping ARKit's `ARCamera.TrackingState`
callback. On degraded/limited/lost tracking, both overlays show a blocking recovery
prompt ("hold steady" / "find a well-lit area") and suspend arrow/fog updates until
tracking recovers — production scanning happens in real-world lighting and motion
conditions, not demo conditions, and silently showing stale guidance during a tracking
failure would produce bad data without the user knowing.

### 1.5 Expected-litter markers and rectification credit

On `ROUTE` mode start, fetch current (`isCurrent=true`, `state='dirty'`)
`worldObservations` for the route's corridor once via the existing
`GET /api/map/observations` endpoint, and render an `ExpectedItemMarker` in the overlay
at each location. This is the "the app shows where it expects trash while you're
walking" behavior requested this session.

**Rectification is explicit-confirm, not passive auto-detect:** the user must aim the
camera at the marker and tap confirm. Passive auto-crediting on "walked within range,
AI saw nothing" was considered and rejected — it can't reliably guarantee the camera
actually covered the spot, and false-positive credit for money/points-adjacent actions
is a real risk given this app's payout economics. Confirming triggers a new
`POST /api/scans/[id]/rectify` endpoint that calls `recordWorldObservation` with
`state='clean'` (using the existing supersession logic — no new write-path logic needed
there) and emits a `rectification_credit` event that Phase 5's points calculation and
Phase 6's reward UX both consume.

### 1.6 Client emission contract

Both overlays batch and flush `scanCoverageEvent` records (§0.2) as the user moves
through a session — this is the primary producer path for the Phase 0 coverage ledger.
If the coverage-write endpoint is unreachable, events queue locally and retry; the
overlay's live fog-of-war (session-local, in-memory `CoverageTracker`) is unaffected
either way, since it doesn't depend on the network round-trip.

### 1.7 Non-goals for this phase

Android/Desktop AR-overlay UI (out of scope, consistent with existing
`ArScanner`/`ArRouteOverlay` stubs — a future ARCore-based spec, sharing this phase's
event schema per §0.2); wiring `GET /api/map/coverage` into the live guidance engine for
cross-session never-scanned/stale indication in-camera (fast-follow once Phase 0 ships
the backend ledger — this phase's guidance stays session-local coverage in the meantime).

---

## 2. Phase 2 — Backend canonical storage (DGX + Cloudflare R2)

### 2.1 Storage split

- **Cloudflare R2** — raw sensor blobs (LiDAR depth frames, capture video, point
  clouds). S3-compatible, zero egress fees — material here because DGX repeatedly
  re-pulls this data for Phase 3 processing, and egress-metered storage would make that
  expensive at any real scale.
- **Neon Postgres** (existing) — event-log metadata only: `scanCoverageEvents`,
  `worldObservations`, R2 object keys, device/quality-tier info. Never holds raw blobs.
- **DGX Spark** — the processing tier, not the durable store. A scheduled/triggered
  worker pulls new raw blobs per geo-cell from R2 once enough coverage accumulates for
  Phase 3 reconstruction, and writes processed outputs (splat assets) back to R2. A
  single physical device should not be the only copy of canonical global scan data —
  durability lives in R2 + Postgres; DGX's role is compute, which is exactly what "build
  cleaned-up splat and 3D environment data" requires from it.

### 2.2 Ingestion path

Mobile app uploads raw capture blobs directly to R2 (presigned upload URLs, issued by a
new `POST /api/scans/[id]/upload-url` endpoint) alongside the existing
`scanCoverageEvents`/`worldObservations` metadata writes — the metadata write and the
blob upload are independent (metadata lands even if the blob upload is still in flight
or retrying on a bad connection).

### 2.3 Non-goals for this phase

Real-time/streaming ingestion (batched upload is sufficient — nothing in this system
needs sub-second raw-data availability); any DGX high-availability/redundancy work
(explicitly out of scope — DGX processes, it does not need to be highly available for
the durable data to be safe, since R2 holds the canonical raw copies).

### 2.4 Data lake design: open formats + explicit provenance, both at once

**Explicit direction from this session:** the digital-twin/world-model data should be
**both** explicitly linked back to the scans that produced it **and** independently usable
as a standalone data lake — extendable for other purposes (SuperSplat, Blender, or any
future tool), not locked into a GCC-only format that requires going through this app's API
to make sense of. These aren't in tension; they're two different layers of the same
storage design:

- **The files themselves are the standalone layer.** Store processed 3D outputs in
  industry-standard, open formats, not a proprietary GCC schema: point clouds as `.ply`
  (or `.laz` for large-scale lidar sets), meshes as `.glb`/`.gltf`, and Gaussian splats as
  `.ply` — the de facto standard splat format SuperSplat, PlayCanvas, and most viewers
  already read natively. This means a signed/public R2 object URL can be opened directly
  in Blender or SuperSplat with **zero GCC-specific tooling** — the asset is self-contained
  by construction, not by a custom export step someone has to build and maintain later.
- **R2 bucket layout is the browsable, documented backbone**, not an opaque blob dump:
  ```
  raw/{locationCellId}/{deviceId}/{capturedAt}.{ext}      -- per-session captures
  reconstructed/{locationCellId}/{version}/pointcloud.ply -- merged, per §3's pipeline
  reconstructed/{locationCellId}/{version}/splat.ply       -- trained Gaussian splat
  reconstructed/{locationCellId}/{version}/mesh.glb        -- optional mesh export
  ```
  This layout is deliberately meaningful on its own — `locationCellId` is the same grid key
  `worldObservations`/`scanCoverageEvents` already use (§0), so the connection between "a
  file in the data lake" and "a place in the world model" is legible from the path alone,
  without a database lookup.
- **The Postgres-side link is the explicit-provenance layer.** New table
  `reconstructionAssets` (`locationCellId`, `assetType` ['pointcloud'|'splat'|'mesh'],
  `format`, `r2Key`, `version`, `sourceScanCoverageEventIds` [jsonb array — which specific
  events fed this reconstruction], `activatedAt`, `createdAt`) — this is what makes "this
  splat came from these specific scans, by these specific devices/workers, on these
  specific dates" a real, queryable fact, not just an inference from folder structure.
  Every asset Phase 3's pipeline produces gets a row here at write time; nothing is
  reconstructed without a provenance record.
- **Net effect:** internal tooling (the map, the app, future analytics) queries
  `reconstructionAssets` joined against `worldObservations`/`scanCoverageEvents` for the
  "this is GCC's data, connected to GCC's world model" view. External/creative tooling
  (Blender for authoring, SuperSplat for viewing/sharing, any future pipeline) just needs
  the R2 URL — the open format and documented path structure make it usable standalone,
  with zero coupling to GCC's own database or API existing at all. Same underlying files,
  two legitimate ways to consume them.

This is a schema addition to Phase 2 (the `reconstructionAssets` table) and a format/path
convention for Phase 3's outputs — not a new phase. Both are cheap to build now, before any
reconstruction pipeline exists, precisely because retrofitting open-format-and-provenance
onto an already-running proprietary pipeline later would be real, avoidable rework.

### 2.5 DGX as working-set load point; admin navigable-environment viewer

**Explicit direction from this session:** R2 stays the full canonical store (§2.1 —
durability, all areas, all history), but the DGX should be the place admin/data-viz tooling
actually *works with* data — pulling down and holding one area's cumulative scan data at a
time, not the whole world, so it can examine a normalized, navigable 3D environment built
from everything scanned there over time.

**Working-set fetch, not a second durable copy.** A new admin-scoped endpoint
(`POST /api/admin/reconstructions/load-area`, `{ locationCellIds: string[] }` or a bbox)
triggers the DGX to pull the current `reconstructionAssets` rows for those cells plus their
underlying raw scans from R2 into local scratch storage — explicitly a **cache with a TTL**,
not a new source of truth. If the DGX disk fills or the process dies, nothing is lost;
R2 is re-fetched. This keeps §2.1's "DGX is compute, not durable storage" principle intact
while giving admin tooling the fast local access it needs to actually browse an area.

**The navigable-environment viewer: real research on this, not assumption.** The
specific technology the "walkable environment from a Gaussian splat" request pointed at —
NVIDIA's Lyra 2.0 — was researched directly this session and turns out to be the wrong
tool, on three independent grounds, not just one:

1. **It's generative, not reconstructive.** Lyra 2.0 takes a single photo, generates an
   imagined video walkthrough via a video diffusion model, then reconstructs *that
   invented video* into 3DGS. It does not take existing real capture data and make it
   navigable — architecturally the opposite of "browse our actual cumulative scan data."
2. **Hardware mismatch.** Its video backbone is a 14B-parameter diffusion transformer
   (Wan 2.1-14B), benchmarked on a single H100 at several minutes per short walkthrough.
   GB10 has roughly 1/85th an H100's BF16 throughput and 1/12th its memory bandwidth —
   this would very plausibly take hours per scene here, before even accounting for the
   Blackwell sm_120 kernel-compatibility traps this device already has documented history
   with (`cdx-dgx-spark` skill).
3. **License bars commercial use outright**, independent of the other two: Lyra's model
   weights ship under NVIDIA's "Internal Scientific Research and Development Model
   License," which explicitly prohibits production use and "generating works for sale."
   This alone rules it out for GCC regardless of fit or hardware.

**What actually solves this, available today, already in this project's toolkit:**
**SuperSplat's Walk Mode** (open source, PlayCanvas, MIT-style license, browser/WebGL —
already referenced in this project's `cdx-splat-pipeline` skill for splat viewing). It
ships exactly the missing capability: first-person click-to-walk/WASD navigation through
a *real* Gaussian splat, via a voxel-based collision system — `splat-transform` voxelizes
the splat and flood-fills the navigable interior from a seed point into a watertight
collision mesh, with one-click Indoor/Outdoor/Object collision presets. This is
non-generative by design, which is actually the correct property here: an admin wants to
walk through *what was actually scanned*, not a plausible-looking hallucination filling
gaps in coverage. It's WebGL rendering with no heavy model inference, so it runs
comfortably on GB10 (or, honestly, on any machine — the DGX's role here is serving the
data, not doing GPU-bound rendering work for this specific feature).

**Concrete shape:** the working-set loader (above) stages a cell's `splat.ply` locally;
a thin admin-only web view (could live in the existing Next.js admin console, or as a
DGX-hosted local tool per this project's existing pattern of admin/data-viz surfaces)
embeds SuperSplat's viewer against that staged file with Walk Mode + collision enabled.
"The system knowing what to expect" — surfacing where the reconstruction is stale, where
`worldObservations` disagrees with what's rendered, where activation-threshold coverage
(§3.1) hasn't been met yet for a sub-area — is a real, valuable extension of this viewer
once it exists, but is scoped as a later addition to this feature, not required for the
first working version.

**If true generative gap-filling is wanted later** (imagining plausible detail for areas
genuinely never scanned, distinct from browsing what was scanned), that's a legitimately
separate, harder research bet — track it as its own future exploration, not a blocker on
shipping the real-data walkthrough viewer now.

---

## 3. Phase 3 — 3D reconstruction / splat pipeline

**This is flagged as research-risk, not a standard build task.** It is a different
problem in kind from the existing `cdx-splat-pipeline` (which turns one generated image
into a splat via synthetic multi-axis video generation). This phase needs to **fuse many
independent people's real-world LiDAR captures of the same physical area**, taken at
different times, lighting, and devices, into one coherent reconstruction — closer to
crowdsourced SLAM/photogrammetry registration than to generative splat creation.

### 3.1 Pipeline shape, informed by Niantic's documented VPS approach

Niantic (Pokémon GO/Ingress/Scaniverse) solved exactly this problem — fusing millions of
independently-submitted consumer LiDAR/camera scans of the same real-world locations into
one persistent, centimeter-accurate 3D model (their Visual Positioning System). Their
publicly-documented pipeline is a strong, validated template, not a from-scratch guess:

1. **Per-scan segmentation** — split/clean each individual capture (their docs mention
   auto-splitting scans over 60s) and correct GPS drift before anything gets compared
   across sessions.
2. **Conversion to a localization-map representation** — a specialized intermediate
   format built for precise spatial matching, not the raw mesh itself.
3. **Spatial-relationship solving** — GPS narrows the candidate search space for "which
   prior scans overlap this one," then global bundle adjustment aligns overlapping scans
   into one coherent frame and rejects outliers that don't register cleanly. This maps
   directly onto our earlier "ICP or learned registration" idea, but scoped as GPS-gated
   (cell-scoped) rather than open-ended, which is a meaningfully cheaper search space.
4. **Dense reconstruction with redundancy filtering** — merge the aligned scans into one
   textured/point-cloud representation per location, rather than keeping N separate
   meshes.
5. **Activation threshold** — critically, **a single scan is never trusted alone.** A
   location doesn't "activate" into the canonical model until enough independent,
   well-registered scans accumulate. The model is continuously updated as further scans
   arrive, not frozen after first coverage. This directly answers the spike's open
   question about a "quality threshold" — the answer is a minimum-independent-scan-count
   gate before promoting a location's reconstruction to canonical, not a single-scan
   quality score.

For GCC, the Gaussian-splat training step (§ below) is the same as originally proposed —
this addendum only sharpens steps 1-3 (segment → GPS-gated align → bundle-adjust) and
adds the explicit activation-threshold gate as a first-class pipeline stage, both pulled
directly from Niantic's own documented approach rather than invented from scratch:

1. Per-session mesh/point-cloud segmentation + GPS-gated registration against
   previously-accumulated scans for that cell (bundle adjustment, outlier rejection).
2. Accumulation into a merged point cloud per geo-cell, redundancy-filtered as new
   sessions register successfully.
3. **Activation gate**: a cell's reconstruction is not promoted to "canonical" until a
   minimum independent-scan-count threshold is met (exact number is the research spike's
   job — Niantic's own public materials don't give a number either, so this isn't a gap
   unique to us).
4. Gaussian-splat training from the merged, activated cloud on DGX's Blackwell GPU.
5. Processed splat assets written to R2 as standard `.ply` Gaussian-splat files at the
   documented `reconstructed/{locationCellId}/{version}/` path (§2.4), with a matching
   `reconstructionAssets` row recorded at write time — served via CDN for the future viewer
   surface (out of scope for this spec — no viewer UI is being designed here), and directly
   openable in SuperSplat/Blender without any GCC-specific tooling, per §2.4's data-lake
   design.

### 3.2 Trust-scored review, borrowed from Wayfarer

Niantic's Wayfarer program gates *review influence* (not just submission) by a dynamic
per-reviewer "agreement score" — how often a reviewer's vote matches the eventual
majority — rather than a fixed rank. Applied here: once GCC has enough scan volume for
disputes to matter (a scan someone flags as wrong/stale/fraudulent), weight dispute
resolution by each flagging worker's historical agreement rate rather than first-flag-wins
or pure staff review. Not required for Phase 0-3, but the `scanCoverageEvents`/
`worldObservations` event-sourced design (§0) already doesn't preclude adding this later —
flagging it here so a future moderation-tooling spec doesn't have to redesign the data
model to support it.

### 3.3 Non-goals for this phase

Any commitment to a specific registration algorithm, training cadence, or exact
activation-threshold number — those are the research spike's job, not this design's
(Niantic's own public materials don't disclose their exact threshold either). No
splat-viewer UI. No trust-scored review tooling (§3.2 is a data-model compatibility note,
not a feature being built now).

---

## 4. Phase 4 — Map visualization

### 4.1 Litter rendering: photo-derived billboard sprites

At close zoom, crop the litter item out of its capture photo (reusing the on-device
segmentation already needed for AI detection — `onDeviceAiResult`), alpha-matte it, and
render as a size/rotation-scaled image billboard anchored at the real map location.
Crossfades to the existing heatmap layer at the existing zoom-14 threshold
(`SPOT_ZOOM.DOTS_MIN`/`HEATMAP_MIN/MAX/FADE_OUT` in `design-tokens.ts`) — reusing the
crossfade mechanism already built and tuned, not inventing a new one.

### 4.2 Tile freshness overlay — explicit lesson applied

Grid-cell overlay (same cells as `scanCoverageEvents`/`cellCoverageSummary`), **not** a
recolor of the base MapLibre style (OpenFreeMap tiles are third-party; the base style
can't be repainted). Grey fill = never scanned, transparent = scanned within 24h (base
heatmap/billboard layer shows through normally), amber hatched = stale (>24h).

This team has already tried and reverted a full-earth uniform tint overlay
(`SPOT_CLEAN_ZONE_BASE`, commits `608ea99`/`86cb757`) for painting the entire map with
zero data-driven variation, hiding roads and labels, and reading as "can't tell what's
on the map." This design differs in kind — cell coloring is per-location and
data-driven, carries real information — but the risk of visual dominance is still real
in the early rollout period when most of a city is genuinely unscanned. Mitigations,
both required, not optional:

- Restrict the overlay to close zoom only (same threshold the dot/heatmap crossfade
  already uses) — never render it as a city-wide wash at low zoom.
- Make it a toggleable layer ("show scan coverage"), off by default until there's
  enough real coverage data for it to look like signal rather than a grey city.

### 4.3 New endpoint

`GET /api/map/coverage?bbox=...` (from §0.4) backs this layer directly.

---

## 5. Phase 5 — Points economy overhaul

Computed from `cellCoverageSummary`/`scanCoverageEvents`, distance-tracked as directed
this session — four distinct categories, weights admin-tunable config rather than
hardcoded:

1. **New area** (cell never scanned before) — highest points-per-meter.
2. **Stale re-scan** (cell last scanned >24h ago) — meaningful points-per-meter, less
   than new-area.
3. **Fresh re-scan** (cell scanned within 24h) — near-zero, to remove incentive to farm
   the same block repeatedly in one sitting.
4. **Rectification** (confirmed-clean at a previously-expected-dirty spot, §1.5) — flat
   bonus on top of any distance points earned in the same pass, since this is a rarer,
   higher-effort, higher-value action than routine coverage.

### 5.1 Non-goals for this phase

Exact numeric point values (config, tunable post-launch, not an architecture decision);
unifying the mobile-side `routeScans.pointsAwarded`/`ScanService.calculatePoints` path
with the web `points_ledger`/`pointsActionEnum` model — these are two separate systems
today per this session's earlier investigation, and merging them is real but separable
work; flagged here so it isn't silently forgotten, not solved in this spec.

---

## 6. Phase 6 — Reward feedback UX

Every beat below maps to a real, already-existing (or Phase-0-5-introduced) backend
state transition. None of them invent a reward for something the backend hasn't
actually committed to — this app moves real civic labor and real money, and celebrating
a payout that isn't guaranteed yet (jobs are admin-gated, no auto-payout) would be a
dark pattern, not good UX.

| Trigger (real state transition) | Feedback |
|---|---|
| Coverage cell clears during a live scan (`CoverageTracker`/`ObjectCoverageTracker` state change) | Haptic tick + fog-dissolve micro-animation, continuous during the activity |
| Hotspot logged (`POST /api/scans/[id]/hotspots`) | Celebratory beat + running point counter increment |
| Scan session complete | Animated count-up of session points, with new-area/stale/rectification bonuses called out individually (specific reasons, not an opaque total) |
| Job submitted (`pending_review`) | Honest "pending admin review" state — no celebration yet, since no money is guaranteed |
| Job **approved** (admin action, existing `jobApproved` push template — currently unused) | Real celebration beat — money is now guaranteed even though it hasn't landed |
| Payout **landed** (`payout.paid` Stripe webhook, existing `payoutSent` push template — currently unused) | Second distinct beat — "$X is in your account" |
| Rectification confirmed (§1.5) | Distinct "you fixed something" framing, separate from routine scan-coverage feedback |

### 6.1 Implementation notes

Wires the two dead push templates identified in the same-day-payouts spec
(`site/src/lib/notifications`, `jobApproved`/`payoutSent`) to their real trigger points —
this closes a gap that spec explicitly left open ("push half-built... never called"),
rather than building new notification infrastructure.

### 6.2 Non-goals for this phase

Streak/momentum mechanics (consecutive-day scanning, weekly goals) — genuinely optional
engagement-layer work, not tied to a real economic/civic event the way every row above
is, and lower priority within this phase; can be added later as a thin aggregation over
existing data without changing anything else in this spec.

---

## 7. Cross-cutting risk carried forward: bystander privacy / consent

The shipped 2026-07-01 spec flagged this as open and unresolved, noting it "becomes more
relevant as scanning becomes more visible/gamified." This spec makes it *more* relevant
still: photo-billboard litter rendering (§4.1) puts real captured photos on a public map
surface, and the broadened world-observation domains (§0.3) — construction, land use —
inherently point cameras at more than litter, including private property and
potentially bystanders. Private-property *no-scan* exclusion zones already exist
(2026-07-01 spec, Initiative 3), but that's a different guarantee than *no public
display of a photo that happens to capture a person or a house number*. This spec does
not resolve that policy question — it is flagged again, explicitly, so it isn't lost a
second time before Phase 4 ships photo billboards to a public map.

### 7.1 Concrete precedent: why this can't stay indefinitely deferred

This session's research into Niantic's crowdsourced world-model approach (§3) surfaced a
direct, current cautionary example, not a hypothetical. Niantic collected roughly 30
billion scans since 2021 via Pokémon GO and Scaniverse to build its VPS/"Large Geospatial
Model." As of December 2025, the retained entity (Niantic Spatial, after the games
business was sold to Scopely) partnered with a defense contractor to fuse that
ground-level scan data with military drone navigation for GPS-denied operations. Niantic's
public position is that game-player data itself wasn't part of the deal, but the company
has acknowledged the underlying navigation model was trained on player scan data —
collected under consent language that never mentioned defense/military use as a possible
downstream application. This produced real public backlash specifically because the
original purpose-limitation was vague enough to permit a use contributors never agreed to.

**The direct lesson for GCC:** scan data here will more frequently and more
identifiably capture private property, addresses, and bystanders than a casual Pokéstop
photo does (this app's whole purpose is scanning specific real-world locations at
job-relevant resolution, not gamified point-of-interest discovery). Before Phase 2 (raw
scan storage) or Phase 4 (public photo-billboard rendering) ship, the ToS/consent
language covering scan collection needs to be **narrow and explicit** about permitted
uses (job verification, before/after proof, aggregate 3D reconstruction for the
platform's own map) and should explicitly exclude undisclosed secondary uses. This is
still a policy decision this spec doesn't make unilaterally — but "flag it and move on"
is no longer sufficient framing given a live, current example of exactly this failure
mode; it should be resolved before those two phases ship, not whenever is convenient.

---

## 8. Open decisions carried forward, not resolved by this design

- Bystander privacy/consent policy (§7) — still unresolved, now higher-stakes.
- Exact `itemType`/new-domain taxonomy values — reuses whatever vocabulary
  `trashTypes`/`onDeviceAiResult` already produces for litter; new-domain taxonomies
  (vegetation/construction/land_use) are undefined until their detection work is
  scoped.
- Splat pipeline registration algorithm and quality thresholds (§3) — research spike,
  not this document.
- Mobile-side vs. web-side points-system unification (§5.1) — flagged, not solved.
- Streak/momentum mechanics scope (§6.2) — deferred, optional.

---

## 9. Phasing / sequencing

- **Phase 0** (backend, DGX+web): `scanCoverageEvents` table, `cellCoverageSummary`
  rollup, `worldObservations.domain` column, two new endpoints. No mobile dependency —
  can start immediately, ships independently.
- **Phase 1** (iOS, DGX+MacBook loop): builds on Phase 0's emission contract but its
  session-local guidance works without Phase 0 being live — coverage events can start
  as a stub sink and get wired to the real endpoint once Phase 0 ships. Highest
  iteration cost (native AR rendering, MacBook-dependent testing).
  - Route-mode arrow: shovel-ready, extends existing scaffolded code.
  - Spot-mode guidance: net-new, larger.
  - Expected-litter markers/rectification: depends on Phase 0's coverage data existing
    for the "was this ever scanned" distinction to be meaningful, though the marker
    fetch itself only needs the already-live `worldObservations` endpoint.
- **Phase 2** (backend infra): R2 provisioning, DGX processing worker skeleton, upload
  path. Depends on Phase 0's metadata schema; the R2/DGX plumbing itself has no mobile
  dependency.
- **Phase 3** (research spike first, DGX): depends on Phase 2's raw-data ingestion
  existing to have real data to register against. Highest uncertainty — timeline is
  genuinely unknown until the spike concludes.
- **Phase 4** (web map): depends on Phase 0's `cellCoverageSummary` for tile freshness;
  litter-billboard rendering only depends on already-live `worldObservations` data and
  can ship independently of the freshness overlay.
- **Phase 5** (backend + mobile points): depends on Phase 0's coverage ledger existing
  as the source of distance-tracked calculation.
- **Phase 6** (mobile + backend, cross-cutting): the job/payout reward beats depend only
  on the already-shipped payout state machine and can start immediately, independent of
  every other phase. The scan-coverage reward beats depend on Phase 1's guidance overlay
  existing to hang haptics/animations on.

## 10. Success metrics

- A worker inside `ArRouteOverlay` sees a live directional arrow toward the nearest
  uncovered area, not just fog-of-war and a boundary — closing the specific gap this
  spec targets first.
- The map can distinguish, per cell, "never scanned" / "scanned, fresh" / "scanned,
  stale" — not just "has a dirty observation or doesn't."
- A rectification (expected-dirty, confirmed-clean) flow produces a real credited event
  end-to-end, from camera confirm to points ledger.
- Every completed job produces two distinct, real push-notification moments (approved,
  paid) instead of zero.
- No feature in this spec celebrates money or points before the backend has actually
  committed to them.
