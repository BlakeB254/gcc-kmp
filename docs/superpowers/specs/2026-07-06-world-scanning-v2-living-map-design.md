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
