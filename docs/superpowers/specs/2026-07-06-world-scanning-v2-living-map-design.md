# World Scanning v2 — The Living Map

**Date:** 2026-07-06
**Status:** Draft (authored on Blake's "take the initiative" delegation; builds on
`2026-07-06-scan-coverage-quality-design.md`, which must ship first)
**Goal:** Make GCC scanning behave like a world-scale 3D-scanning app: every session
captures reconstruction-grade data, the map updates near-live, and what users scanned
appears on the map with true position, size, and shape — cleanliness heatmap from afar,
geometry up close.

## Why this order

The coverage-quality PR (in flight) makes the map *honest* — measured raycast coverage,
two quality tiers, indoor retraction. v2 makes it *rich* (full capture), *alive*
(streaming), and *physical* (shapes). Pillars are ordered by dependency and value; each
pillar is its own spec→plan→PR cycle.

---

## Pillar 1 — Full-session AR capture → R2 (the reconstruction feed)

**Blake's ask:** "recording the entire session to be captured and sent to the R2
storage… using the lidar and positioning data for the map at the same time."
**Feasibility settled:** one ARSession feeds both — the live raycast fan and a recorder
read the same frame stream.

- **`ArSessionRecorder` (Swift, iosApp/GccArNative pattern):**
  - HEVC video from `frame.capturedImage` via `AVAssetWriter` (720p, ~2-4 Mbps,
    device-clock timestamps; append off-main via the frameLock'd lastFrame accessor —
    NEVER `session.currentFrame` off-main).
  - Per-frame JSONL sidecar: `timestampMs, cameraTransform[16], intrinsics[9],
    trackingState, latestGpsFix{lat,lng,acc,heading}, gateState` — the 6-DoF trajectory
    a reconstruction pipeline needs, geo-referenced with the SAME pairing math the fan
    uses.
  - LiDAR depth: `smoothedSceneDepth` (256×192 float + confidence) keyframes at 2 Hz,
    zstd-compressed; full-rate only behind a debug flag.
- **Thermal/battery policy:** at thermal `.serious` drop video to keyframe stills +
  keep the sidecar; at `.critical` pause capture entirely (scan continues — capture is
  never allowed to degrade the live scan). Recording state chip in the scan UI.
- **Upload:** chunked (~64 MB) resumable via the existing presigned-PUT queue
  (token-free siteClient — the R2 SigV4 gotcha), keys
  `raw/{gridCell}/{sessionId}/capture/{chunkN}`; session manifest gains a
  `captureChunks` list. Default policy: upload on Wi-Fi or charging; user setting to
  force cellular. Chunks persist on-disk until acked (fixes the in-memory recorder
  app-kill loss too — Pillar 6 folds in here).
- **Privacy tie-in:** the INDOOR purge/retraction window applies to capture chunks the
  same way it applies to coverage — trailing-window video segments are truncated before
  upload.

## Pillar 2 — Live map streaming (read-side freshness)

**Blake's ask:** "that data being livestreamed right to our gcc map."
Write side is already near-live (30s event flush). The gap is readers.

- **Server:** `GET /api/map/coverage` and `/api/map/observations` gain
  `since=<ISO>` delta params (rows with `lastScannedAt`/`updatedAt >= since`), cheap on
  the existing summary table.
- **Client:** map screens poll deltas every 20s while foregrounded (Vercel
  Hobby-friendly — no WebSocket/SSE infra), merge into the tile/pin state; scanning
  user's own session also pushes credited cells straight into the local map cache so
  their own map is instant.
- **Result:** another user watching the map sees tiles turn green within ~30-50s of the
  scanner walking them.

## Pillar 3 — Shape-accurate landmarks at close zoom

**Blake's ask:** "location accurate landmarks… size and shape accurate as well at close
zooms and from afar obviously represented by the heatmap rated by cleanliness."

- **Client capture:** landmark auto-registration (`registerElementIfAny`) already has
  raycast distance + saliency bbox — compute `widthMeters` (the trash path's
  `physicalWidthMeters` math), plus `yawDegrees` from camera heading at capture; attach
  to `ObservedSpotItem.attributes`.
- **Server:** `world_observations.attributes` carries
  `{widthMeters, depthMeters?, yawDegrees}`; supersede-merge keeps them.
- **Map rendering:** at zoom ≥ 17, landmark/litter observations with extents render as
  oriented footprint polygons (ellipse for vegetation, rect for fences/mattresses…)
  with the type glyph centered; below 17 today's glyphs; below detail zoom the
  cleanliness heat layers own the story (unchanged).
- **Trash too:** confirmed items already carry `estimatedWidthMeters` — same rendering
  path, so a mattress reads as a mattress-sized rectangle on the map.

## Pillar 4 — Scan score (the 3D-scanner feel, completed)

- Session summary gains a **scan score**: coverage% × close% × steadiness (tracking
  NORMAL ratio from ArSessionHealth) → A-F grade + "what would make it better" line
  ("60% of your area is sweep-only — walk closer next time").
- Split points (shipped in the current PR) already pay the economics; the score is the
  legible face of it. Streaks/badges deferred until Blake asks.

## Pillar 5 — Rescan prioritization (what the world needs next)

- Server: per-cell priority = staleness × (grazed-only boost) × nearby-dirty weight;
  `GET /api/map/coverage` returns it.
- Explorer map gains a "needs scanning" lens (priority choropleth); block/bounty
  surfaces can later rank by aggregate priority.

## Pillar 6 — Session resilience (folded into Pillar 1's disk queue)

- `EnvScanSessionRecorder` samples persist incrementally to disk; app-kill mid-scan
  recovers the manifest on next launch and uploads it (today it's lost — PR #85 audit).

---

## Pillar 7 — Extended sight coverage + organic rendering (Blake, 2026-07-07, from field screenshot)

**Ask:** "the scan could see the building fronts across the street so it should have a
lightly shaded depiction… extend the cone to where the user can see… and the squares
shouldn't be squares."

- **Third tier `SEEN`:** raycast-fan hits beyond GRAZE_RANGE (10m) up to
  `SEEN_RANGE_METERS` (~55m) credit cells at the lightest tier — "camera observed it,
  point data exists" — instead of being discarded. Monotonic SEEN<GRAZED<CLOSE.
  Server quality enum += 'seen' (points: 0/m v1 — sight alone isn't work); minimap +
  main map get a third, fainter wash; AR fog unchanged (lifts only on grazed+).
  Physics note: beyond LiDAR range, hits come from estimated planes/feature points —
  positional error grows with distance; acceptable for a light wash, must stay
  visually subordinate to grazed/close.
- **Organic coverage rendering:** replace per-cell squares with smoothed union
  outlines per contiguous credited cluster, per tier (marching-squares over the 3m
  cell set → rounded path; drop grid strokes). Session minimap AND main map, so
  coverage reads as swept territory, not graph paper.
- **Coverage everywhere at close zoom:** both My Area and Explorer render the
  coverage layer; fetch follows the viewport (delta polling gives cheap refresh).
- **Experience arc:** close ground-panning remains the only path to "thoroughly
  scanned" (trash/grass/plant detail); distant multi-directional scanning feeds the
  world model and paints SEEN — the shading teaches the difference.

Sequenced as the round after Pillars 4/5 (same tracker/server/map files).

## Pillar 8 — Scan-supersedes-spots + map language + AR focus (Blake, 2026-07-07, product law)

1. **Scanning overwrites spots.** A scan covering an area REMOVES old spot pins there:
   nothing found → clean scanned area; litter found → accurate close-zoom display
   (observations/footprints) + heatmap contribution at distance. The PR #65 supersede
   (15m of anchor + scanned items) must be strengthened to key on the scan's actual
   COVERED CELLS (grazed+ tiers), and verified working with the current scan flow.
2. **Spots removed entirely — replaced by honest litter visualization (Blake
   revision, same day: no internal dot layer either).** Close-zoom tiles on BOTH the
   app map and the website render the actual nature + positioning of litter from
   world observations: per-item positions/types/extents (Pillar 3 footprints are the
   foundation) with distribution-aware rendering — a fenceline scatter reads as a
   linear cluster, dispersed litter reads as spread marks, a genuine pile reads as a
   pile. NOT one generic pill at the block bookend. The best mess photos (detection
   frames + report media) are preserved per observation/cluster and surface on tap,
   alongside weight/material estimates from close scans. Spot-pin layers get removed
   from mobile GccMap and web CombinedMap; the heatmap remains the far-zoom language.
3. **Scanned vs unscanned = unmistakable.** The coverage wash must clearly split the
   world into "app has been here" vs not (current 0.14-0.20α tints are too subtle —
   pair with Pillar 7's organic rendering; keep basemap labels legible).
4. **AR tap-to-focus.** Tapping the camera view heavily biases detection at that
   region: run Vision/saliency on the tapped ROI + raycast there immediately, lowering
   the promotion threshold for that area for a few seconds.
5. **Identify → approach → advanced data.** On litter identification: reticle/circle
   (exists) + explicit approach prompting ("go up to it — scan around it") escalating
   to close-scan capture of advanced data (estimated weight, materials — the confirm
   card's estimators exist; the guided approach flow is the new part).

## Explicit non-goals (v2)

- On-device reconstruction (DGX owns it; phone ships raw truth).
- WebSocket/SSE push (polling deltas are Hobby-plan-safe and sufficient at current scale).
- Android capture parity (iOS-first per standing direction).

## Sequencing

1. **Pillar 1 + 6** (one PR): recorder, sidecar, depth keyframes, disk queue, chunked
   upload, thermal policy. Biggest unlock — DGX gets reconstruction-grade data.
2. **Pillar 3** (one PR): extents capture → attributes → footprint layer.
3. **Pillar 2** (one PR): delta endpoints + client polling merge.
4. **Pillar 4, 5** (small PRs): score card; priority lens.

Each pillar gets its own implementation plan before code, per house workflow. All work
sequenced AFTER the coverage-quality PR merges (shared files: SpotScreen, overlays,
manifests, map layers).
