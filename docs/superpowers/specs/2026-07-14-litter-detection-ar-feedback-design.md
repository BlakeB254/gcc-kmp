# Litter Detection + AR Scan Feedback — Design (Track 2)

**Date:** 2026-07-14
**Status:** Approved decisions incorporated (Blake, 2026-07-14) — ready for plan
**Scope:** Track 2 of the env-scan product push. Track 1 (3DGS capture
reliability) is `2026-07-14-3dgs-capture-reliability-design.md`.

## Problem

Field-confirmed on Blake's 2026-07-13 walk (session
`env-1783970967094-3wyhss`): 11 detections, all fence/bush landmarks,
**zero litter**; `tap_count = 0`; no AR feedback when anything was tracked;
`estimatedWeightGrams` NULL on essentially every litter observation in prod
(map severity runs on count fallbacks).

Root cause of the detection gap: `analyzeTrash` runs
`VNClassifyImageRequest` on the **whole frame** — scene classification
("this is a picture of a fence"), structurally incapable of localizing a
bottle on the ground (issue #173). Nothing that asks "what is this frame a
picture of" will ever find a cup at 4 m.

## Design principle (Blake, 2026-07-14)

> Objects on ground designated for walking/driving/yards are probably
> litter; exclude the things that belong there (benches, light poles, yard
> signs); close-up dwell and taps are strong trash signals.

**Geometry proposes, exclusion disposes, user intent overrides.**

## Components

### 1. Ground-anomaly proposer (Swift, `GroundAnomalyDetector`)

The phone already knows the ground: LiDAR `sceneDepth` + ARKit plane
anchors (`.floor`/`.road`-classified planes are what the neon grid snaps
to). New `GroundAnomalyDetector` in `iosApp` runs on a throttled cadence
(2 Hz, skipped at thermal `.serious`):

- Take the current depth map + camera transform; for pixels whose world
  point lies within 0.5–6 m and within a candidate ground plane's extent,
  compute height above that plane.
- Connected components with height 0.03–0.4 m and footprint 0.05–1.0 m →
  **candidate blobs**: world position, bounding box, measured dimensions
  (w×h×d), and a stable candidate id (spatial hash; re-observations update,
  not duplicate).
- Emits candidates over the same per-tick drain seam the capture layer uses
  (Track 1 Task 2 pattern) — Kotlin owns all decisions.

No ML here. This is depth arithmetic, and it is the localization the
whole-frame classifier could never provide.

### 2. Exclusion classifier (existing Vision, now aimed at a crop)

For each candidate, crop the camera frame to the blob's projected bounding
box (+15% pad) and run the **existing** `VNClassifyImageRequest` on the
crop. Classification now only answers "is this one of the belongs-here
things?":

- Exclusion labels: bench, pole/street light, sign, hydrant, mailbox,
  bollard, planter, plant/bush/tree/grass tuft, curb/step, car/tire/wheel,
  animal. Confident exclusion (≥0.45) → candidate dropped **silently**
  (Blake 2026-07-14: no AR cue for excluded objects — the view stays
  clean; exclusions are still flight-recorder events for tuning).
- Recognized trash-ish label (bottle, cup, can, bag, paper, wrapper, box…)
  → typed candidate.
- No confident label but right size on walkable ground → `Unknown litter`
  (Blake's standing Option A: unknown is a valid litter type).

### 3. Intent signals

- **Dwell:** camera center within ~8° of a candidate at <2 m for ≥1.8 s →
  auto-promote to scanning (Blake 2026-07-14: "a bit longer than 1.2 s" so
  walking glances don't trigger it; the constant lives in one place for
  field tuning). Bypasses the confidence bar; the exclusion list can still
  veto — silently, per the decision below.
- **Tap:** the existing tap→reticle→confirm-pill path stays authoritative;
  a tap near a candidate adopts it (its geometry/dimensions ride along).
  Separately, Track 2 opens with a device-verified investigation of why
  `tap_count = 0` on the July 13 walk — the flight recorder now shows taps,
  so one instrumented field session settles whether taps fire at all.

### 4. AR scan-completion feedback (Blake's UX, verbatim requirement)

Per accepted candidate, one pooled anchored entity (the frontier-beacon
pooling pattern in `RealityKitArSurface`):

- Reticle appears on the object with a **completion bar that fills** as
  scan evidence accumulates (observation angles + close-range frames +
  depth coverage of the blob).
- At 100%: **scan-complete icon for 1.5 s**, then the AR treatment
  dissolves.
- On completion the item is persisted as an `ObservedSpotItem`
  (provenance `auto_detected`, or `user_confirmed` via tap/dwell-confirm),
  the best crop is saved as its photo, and it appears on the **map and the
  live session minimap** immediately (minimap marker added at completion,
  not at session upload).

### 5. Estimated weight, finally real

`estimatedWeightGrams = volume(w×h×d) × fillFactor(0.3) ×
densityByClass` (coarse class densities: plastic film 40 g/L, rigid
plastic 90 g/L, glass 300 g/L, metal can 120 g/L, paper 80 g/L, unknown
80 g/L). Coarse is fine: the map's gram-based severity tiers
(`SEVERITY_MODERATE_COUNT` fallback today) start working with real data,
and the constants live in one shared object for tuning.

### 6. Telemetry + acceptance

- Every candidate lifecycle event goes to the flight recorder:
  `detection` events gain `source` (`geometry` | `dwell` | `tap`) and
  `outcome` (`accepted` | `excluded:<label>` | `timeout`).
- Field acceptance gate: a staged walk with ~10 planted items (bottles,
  cups, bags) on sidewalk/parkway — ≥70% get reticle + completion from a
  normal walking approach, zero benches/poles/signs tagged, every completed
  item on the minimap live and in `world_observations` with weight set.

## Out of scope

- Server-side / DGX re-detection from uploaded chunks (possible later —
  the same depth data uploads in Track 1's chunks).
- Android parity (detector is LiDAR-dependent; non-LiDAR devices keep
  tap-only flow).
- Trained custom CoreML litter model (revisit only if the
  geometry+exclusion approach misses in the field).

## Resolved questions (Blake, 2026-07-14)

1. Dwell auto-promote: longer than 1.2 s → **1.8 s** (tunable constant).
2. Excluded objects: **completely silent** in AR (flight-recorder events
   only).
