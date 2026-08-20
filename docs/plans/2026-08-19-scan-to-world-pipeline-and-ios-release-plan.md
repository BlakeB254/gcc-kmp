# Scan → World Pipeline & iOS v1.0.9 Release Plan

**Date:** 2026-08-19
**Status:** backbone verdict landed (§3.1: ARKit canonical, VidMap external-footage-only)
**Scope:** end-to-end phone capture → 3D world reconstruction → world twin, the trash-identification stack, iOS scan UX, and the backend/environment-creation features that feed chicago-twin + the GGC game.

## 0. What exists today (audited 2026-08-19)

| Layer | State |
|---|---|
| iOS capture | `ArSessionRecorder` rotating chunks (HEVC + ARKit pose/intrinsics sidecar + LZFSE LiDAR depth + confidence), background R2 upload |
| Capture coaching | `ReconstructionReadinessEvaluator` — advisory only (2m baseline, 60% tracking, depth present), one-line guidance |
| Litter ID | `GroundAnomalyDetector` (LiDAR), tap/draw marking, `LitterCropClassifier.swift` slot (**inert — model never shipped in bundle**) |
| Classifier training | `scripts/litter_model`: TACO cold-start + field-labelled crop flywheel → ultralytics → CoreML export runbook |
| Object Capture | `ObjectCapturePhotogrammetry.swift` — guided orbit + on-device `.reduced` USDZ, bridge-registered |
| Backend | Ktor control plane: R2→Neon scan index, `reconstruction_jobs` (SKIP LOCKED), `recon_worker.py` → `fuse_cell.py` (ARKit poses + depth fusion) → `reconstructed/{cell}/{v}` |
| Pose backbone | `vidmap_runner.py` + WorldMirror prior handoff — P0 on Mapillary bundles was conditional NO-GO (footage protocol) |
| World twin feed | manual; `reconstructed/` assets → chicago-twin export is by hand |

**R2 scan inventory:** 158 sessions in `gcc-scan-storage/raw/`; 26 pass the VidMap bar (≥20s, ≥10 GPS fixes, .mov present). iOS scans are the richest input we have (video + metric ARKit poses + LiDAR depth + 1Hz GPS + labels).

## 1. Target end-to-end pipeline

```
iPhone (ARKit video+depth+poses+GPS, tap/draw labels)
   │  background upload, chunked, resumable
   ▼
R2 raw/{cell}/…  ──► Neon scan_sessions index (exists)
   │
   ▼  reconstruction_jobs (SKIP LOCKED, exists)
recon_worker
   ├─ A. geometry: fuse_cell depth+ARKit poses (exists)
   │     → upgraded by VidMap poses where GPS/ARKit drift (§3)
   ├─ B. litter: per-frame detection on extracted frames (§4)
   │     → litter_cells / observation layers (heatmap, freshness)
   ▼
reconstructed/{cell}/{v}  ──► world-twin export (§5)
   ├─ chicago-twin tiles / prior_camera.json / trees+props
   └─ GGC game world bakes (trash-snapshot, hazards, spawn data)
```

Design rules (kept from current system): raw scans never deleted; recon outputs versioned per cell; every layer carries provenance (session id, model version).

## 2. Capture: what the phone must collect (P2 protocol, now evidence-backed)

VidMap P0 spikes (Mapillary + iOS 2026-08-19) confirm the PRD's capture protocol and quantify it:

- **Frame density matters more than duration**: 81 frames/231s solved geometrically but drifted 29m from GPS (track median 2 frames — features die between frames). Walking-speed sidewalk capture wants ≤1m frame spacing ⇒ the app should keep full-framerate sidecar + ≥2Hz depth (it does) and the *pipeline* should sample ~1 frame/s, not 1/3s.
- **Path shape**: forward-only straight walks are the weakest case. The app's readiness evaluator checks a 2m horizontal baseline; the protocol additions are **turn prompts** and **loop closure** ("end where you started") — GPS path shape is measurable live on-device.
- **Orientation**: .mov is sensor-landscape; pipeline must rotate frames upright before learned-depth stages (build_ios_bundle.py handles this; bake into recon_worker).
- **GPS accuracy gates**: fixes are ~17m at session start, converging to 3–8m — anchor weighting should use per-fix accuracy (already in gps.jsonl).

## 3. Pose/geometry backbone

### 3.1 VidMap — verdict 2026-08-19: NO-GO on iOS footage; ARKit is canonical

Three spikes now: P0 Mapillary (conditional NO-GO, footage), iOS 81-frame (29.4m RMS), iOS 231-frame dense (31.7m RMS — denser sampling tripled tracks but the trajectory still unrolls straight: row-house aliasing, not sampling). The three-way tiebreak against GPS is decisive: **ARKit sidecar poses fit GPS at scale 0.973 with 3.4m median residual** — metric and drift-bounded out of the box.

Consequences:
- **ARKit + LiDAR depth fusion (`fuse_cell.py`) stays the canonical geometry path** for iOS scans. VidMap is reserved for non-ARKit footage (Ray-Ban, third-party video) only.
- The WorldMirror A/B gate (rms < ~2m) is satisfied by ARKit poses on this evidence — **WorldMirror A/B is unblocked for iOS-sourced cells** and is the next recon-lane action.
- Capture protocol still matters, but for ARKit tracking quality (baseline, turns, loop), not for VidMap.

### 3.2 depth-anything.cpp (LocalAI)

Blake-flagged (X 2026-08-17). C++ Depth-Anything inference → depth maps/point clouds from ordinary images, no Python. Evaluate as: (a) server-side depth on frames **without** LiDAR (older devices, Ray-Ban), (b) candidate to replace the DA3 python dependency in VidMap's depth stage on aarch64. Action: bench on the GB10 against the current python DA3 path (quality + wall-clock), one afternoon spike. If it holds, it becomes the portable depth backend everywhere.

### 3.3 On-device Object Capture

Already bridged (guided orbit → USDZ). Keep for item-level models (trash piles, monuments, planters); promote outputs into `reconstructed/{cell}/props/` as georeferenced props for the world twin + game.

## 4. Trash identification stack

| Stage | Today | Upgrade |
|---|---|---|
| On-device real-time | Vision taxonomy + LiDAR ground anomaly | **Ship the trained crop classifier** (the slot is inert) — close the loop: export latest `LitterCropClassifier.mlpackage` into the bundle |
| Classifier model | ultralytics YOLO crop classifier, TACO + field crops | Move training to **YOLO26n/s** (NMS-free, easier CoreML) or **RF-DETR** (Apache-2.0 — Ultralytics is AGPL; decision needed for closed-source app). TACO: annotations CC BY 4.0 but images are mixed-license — keep TACO as pretraining only, ship weights trained on our own field crops |
| Server-side (frames in R2) | none | Batch pass over extracted frames with a self-hosted open-vocab detector (Grounding DINO 1.0 Apache-2.0 / Florence-2) for classes the crop model misses (bulk items, graffiti, dumping); human-verify via existing admin flow before twin writes |
| Flywheel | image boxes + label provenance in manifests (exists) | Add auto-suggested chips ranked by classifier confidence so user confirmation is one tap; every confirmation is a training sample (exists — keep) |

## 5. Backend data creation → world twin / environment creation

1. **Litter layers**: per-cell `litter_density` + `freshness` rasters derived from scan observations (not just events) — publish to Neon + versioned JSON in R2 for the game/twin to bake.
2. **Recon→twin exporter**: formalize the manual step: `reconstructed/{cell}/{v}` → chicago-twin tiles/prior_camera/props, plus game bakes (`trash-snapshot.json`-style) stamped with recon version. One script, idempotent, run by recon_worker on completion.
3. **Environment creation features** (the "create places" side): scanned-cell galleries (exists) + before/after cleaning pairs (exists as bundles) → promote to twin overlays so the twin shows cleaning deltas over time.
4. **QA gates**: keep WorldMirror A/B gated on pose quality (exists); add litter-layer sanity checks (counts vs. manifest totals).

## 6. iOS v1.0.9 release plan

Current: v1.0.8 / build 181 in tree; builds 165–178 were the env-scan overhaul cycle.

**Theme: scan-quality + identification release.**

1. **Capture coaching v2** — beyond the advisory evaluator: Scaniverse-style pattern guidance (perimeter loop + lattice pass for block faces), live loop-closure prompt ("head back to your start"), turn prompts on long straight segments, live coverage mini-map in the scan HUD. All measurable from GPS + ARKit poses already on device.
2. **Ship the litter classifier** — bundle the `.mlpackage`; wire confidence-ranked suggestion chips on observed items; keep tap/draw as override.
3. **Auto-capture option** — Polycam-style: app triggers stills/chunk marks based on motion (AR pose deltas) instead of fixed cadence.
4. **Object Capture promotion** — after a pile/item scan, offer "make 3D model" flow; result uploaded to R2 as a georeferenced prop.
5. **Scan review screen** — post-scan: path on map, readiness score, detected items w/ thumbnails, retry/annotate before upload finalizes.
6. Release mechanics: bump CFBundleShortVersionString to 1.0.9, fastlane `distribute` → external TestFlight → field-validate (scan protocol + classifier) → `submit` per `docs/release/appstore-distribution.md`.

## 6b. Bookmark-derived integrations (from the 2026-08-19 local X-bookmark scrape)

Pulled from `cdx-platform/out/research/x-bookmarks/2026-08-19/bookmarks.jsonl` (295 bookmarks). The ones that change or sharpen this plan:

- **SuperMap (SLAM semantic memory)** — scene memory that tracks what *moved, disappeared, and changed* across passes. This is exactly scan-over-scan **litter delta detection** (what appeared since the last cleaning? what got picked up?). Evaluate as the change-detection layer over our per-cell reconstructions. https://x.com/robotsdigest/status/2089052771341435256
- **Niantic Spatial: collision mesh alongside every Gaussian splat** — machine-readable geometry, not just photorealism; matches our twin's need for navigable/game-usable output from splats. https://x.com/NianticSpatial/status/2087589435185459460
- **4DGS in the browser (WebXR)** — splats + time axis = the natural viewer for **before/after cleaning deltas** per block. Candidate for the site/twin overlay. https://x.com/tokufxug/status/2089531197794005311
- **LocateAnything-3B on DGX Spark** — 450 frames × 14k boxes, open-vocab per-frame detection on *our exact hardware class*. Direct template for the server-side frame-detection pass in §4. https://x.com/aijoey/status/2062133122125201701
- **SAM3 (open source) video object tracking** — track a litter candidate across frames/chunks to dedupe observations and stabilize boxes before they hit the twin. https://x.com/skalskip92/status/2056658463229390936
- **Qwen3.8-27B + RF-DETR consensus labeling** — VLM agrees with local detector, auto-keeping only intersecting boxes; a zero-hand-label way to bulk-verify server-side detections. https://x.com/MaziyarPanahi/status/2089341222733431087
- **CoMe (ECCV 2026): confidence-based mesh extraction from 3D Gaussians** — if/when we splat cells, this is the mesh-export path for game/twin consumption. https://x.com/rsasaki0109/status/2089644723069735414
- **Alibaba Mapping Lab: 1 km² photoreal 3D city from a 2D satellite image in ~10 min** — watch this; a satellite-fill layer could backfill twin blocks with no scan coverage. https://x.com/yohaniddawela/status/2089659558998196656
- **bilawalsidhu's geo-anchored photo/video→splat workflow** ("geospatial memory palace") — the reference UX for scans anchored in place; our version is per-cell and civic, but the authoring flow is worth studying. https://x.com/bilawalsidhu/status/2087285954218279307
- **Ego-OSCAR** (open-source stereo-inertial head-mounted capture) — if we ever outgrow phone-only capture, this is the open hardware path. https://x.com/fpv_labs/status/2088266409210188265
- **Stream3D / GUSH3R / GLINT** — rolling-camera 3D consistency, monocular-video dynamic+static Gaussians, scene transparency — research watchlist for the recon lane. https://x.com/jiqizhixin/status/2087658887772098807 https://x.com/Almorgand/status/2086857724399145193 https://x.com/rsasaki0109/status/2087830266928648699

New work-order entries derived from these are folded into §7 (#8–#10).

## 7. Work order (next actions)
| # | Action | Owner | Status |
|---|---|---|---|
| 1 | VidMap dense re-spike + verdict into §3.1 | agent | **done — NO-GO on iOS; ARKit canonical** |
| 1b | WorldMirror A/B on an iOS-sourced cell (gate now satisfied by ARKit poses) | agent | **unblocked, next recon action** |
| 2 | depth-anything.cpp bench vs python DA3 on GB10 | agent | ready |
| 3 | `build_ios_bundle.py` rotation+GPS handling → fold into recon_worker | agent | built this session |
| 4 | Train/export current litter classifier; bundle into app (MacBook build) | Blake (Xcode) + agent (train) | model runbook ready |
| 5 | YOLO26 vs RF-DETR license decision (AGPL vs Apache-2.0) | Blake | needs pick |
| 6 | Capture coaching v2 UX designs | agent (designer lane) | — |
| 7 | Recon→twin exporter script | agent | — |
| 8 | Server-side frame-detection spike: LocateAnything-3B (or Grounding DINO 1.0) on R2 frames, DGX/GB10 | agent | from bookmarks §6b |
| 9 | Litter delta layer: scan-over-scan change detection per cell (SuperMap-style; start as raster diff on litter layers) | agent | from bookmarks §6b |
| 10 | SAM3 tracking spike: dedupe litter candidates across chunk frames before twin writes | agent | from bookmarks §6b |

## 8. Open items for Blake

- ~~**X bookmarks**~~: found the local scrape (`cdx-platform/out/research/x-bookmarks/2026-08-19/`, 295 bookmarks) — top picks integrated in §6b.
- **License pick**: Ultralytics AGPL (YOLO26) vs Apache-2.0 (RF-DETR) for the on-device detector.
- **TestFlight**: when the classifier + coaching v2 land, we need a MacBook build cycle (181+).
